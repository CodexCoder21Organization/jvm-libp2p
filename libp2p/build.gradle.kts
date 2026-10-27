import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    id("com.google.protobuf").version("0.9.4")
    id("me.champeau.jmh").version("0.7.3")
}

// https://docs.gradle.org/current/userguide/java_testing.html#ex-disable-publishing-of-test-fixtures-variants
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

dependencies {
    api("io.netty:netty-common")
    api("io.netty:netty-buffer")
    api("io.netty:netty-transport")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-codec-http")
    implementation("io.netty:netty-codec-protobuf")
    implementation("io.netty:netty-transport-classes-epoll")
    implementation("io.netty:netty-codec-native-quic")
    // OS-specific bindings
    implementation("io.netty:netty-codec-native-quic::linux-x86_64")
    implementation("io.netty:netty-codec-native-quic::linux-aarch_64")
    implementation("io.netty:netty-codec-native-quic::osx-x86_64")
    implementation("io.netty:netty-codec-native-quic::osx-aarch_64")
    implementation("io.netty:netty-codec-native-quic::windows-x86_64")
    implementation("io.netty:netty-tcnative-boringssl-static::linux-x86_64")
    implementation("io.netty:netty-tcnative-boringssl-static::linux-aarch_64")
    implementation("io.netty:netty-tcnative-boringssl-static::osx-x86_64")
    implementation("io.netty:netty-tcnative-boringssl-static::osx-aarch_64")
    implementation("io.netty:netty-tcnative-boringssl-static::windows-x86_64")

    api("com.google.protobuf:protobuf-java")

    implementation("com.github.multiformats:java-multibase")
    implementation("tech.pegasys:noise-java")

    implementation("org.bouncycastle:bcprov-jdk18on")
    implementation("org.bouncycastle:bcpkix-jdk18on")

    testImplementation(project(":tools:schedulers"))
    testImplementation("org.json:json:20250517")

    testFixturesApi("org.apache.logging.log4j:log4j-core")
    testFixturesImplementation(project(":tools:schedulers"))
    testFixturesImplementation("io.netty:netty-transport-classes-epoll")
    testFixturesImplementation("io.netty:netty-handler")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")

    jmhImplementation(project(":tools:schedulers"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc"
    }

    tasks["clean"].doFirst { delete(generatedFilesBaseDir) }

    idea {
        module {
            sourceDirs.add(file("$generatedFilesBaseDir/main/java"))
        }
    }
}

val kuboVersion = "0.34.1"
val kuboOs = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("linux") -> "linux"
        it.contains("windows") -> "windows"
        it.contains("mac") -> "darwin"
        else -> error("Kubo test binary is not configured for operating system '$it'")
    }
}
val kuboArch = System.getProperty("os.arch").lowercase().let {
    when (it) {
        "amd64", "x86_64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Kubo test binary is not configured for architecture '$it'")
    }
}
val kuboPlatform = "$kuboOs-$kuboArch"
val kuboChecksums = mapOf(
    "linux-amd64" to "4e4b13c600d5a9422f2d0a03e674b65e14defa65dfc6048facdea3a195f967ece0573b8a2292800d902d69fd6ef5aad825b95d8147cbf902f55b60ee0b4c419e",
    "linux-arm64" to "04dd6e9caaf6f287d19a0c11d90f907e578022b4369209830ec00ccb315d865469779013a856bcb72be54a83d2215100ae76dd9181a40cfa920233d1de93c94b",
    "darwin-amd64" to "435b23904eca342730d86cf9207bd29bdee2fabfcf62f24b2a3a1fab771618bc82bd9f4ce3f89f370e93c7c20fc68e551583130ea9b0a58d6aeb776e70f4f3b0",
    "darwin-arm64" to "5abbe269cd1f61dce4bd22d1cfd87be64009de0d80a235303011eb70ef889b043439cef2a8c6487383b724cd44dfb16a1be609c0ca7430c9c7249521b5a98ea3",
    "windows-amd64" to "b139c4bacee6bf7b0221dbee5c5dfe4bf5b2ac0d5c7daa1521b338a9b170276654392fde97feb49230f03dd157c8a72d617b1ee0ff7b2e7f58664cc6d17246f2"
)
val kuboChecksum = kuboChecksums[kuboPlatform]
    ?: error("Kubo test binary is not configured for platform '$kuboPlatform'")
val kuboTools = layout.buildDirectory.dir("test-tools/kubo-$kuboVersion-$kuboPlatform")
val kuboExecutable = kuboTools.map { it.file("kubo/" + if (kuboOs == "windows") "ipfs.exe" else "ipfs") }
val prepareKubo by tasks.registering {
    inputs.property("version", kuboVersion)
    inputs.property("platform", kuboPlatform)
    inputs.property("sha512", kuboChecksum)
    outputs.file(kuboExecutable)
    doLast {
        val directory = kuboTools.get().asFile.apply { mkdirs() }
        val extension = if (kuboOs == "windows") "zip" else "tar.gz"
        val archive = directory.resolve("kubo.$extension")
        val url = URL("https://github.com/ipfs/kubo/releases/download/v$kuboVersion/kubo_v${kuboVersion}_$kuboPlatform.$extension")
        val digest = MessageDigest.getInstance("SHA-512")
        val connection = url.openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        DigestInputStream(connection.getInputStream(), digest).use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == kuboChecksum) {
            "Kubo archive $url has SHA-512 $actual; expected $kuboChecksum"
        }
        copy {
            from(if (kuboOs == "windows") zipTree(archive) else tarTree(resources.gzip(archive)))
            into(directory)
        }
        check(kuboExecutable.get().asFile.setExecutable(true) || kuboOs == "windows") {
            "Could not make Kubo test binary executable: ${kuboExecutable.get().asFile}"
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(prepareKubo)
    systemProperty("libp2p.test.kubo.executable", kuboExecutable.get().asFile.absolutePath)
}

// Keep the build, Android tools and general tests on their compatible runtime.
// Windows assigns every Kubo case to this separate task for the corrected process wait.
val kuboJavaHome = providers.gradleProperty("kuboJavaHome")
if (kuboJavaHome.isPresent) {
    tasks.named<Test>("test") {
        exclude("io/libp2p/transport/quic/QuicKuboTestJava*.class")
    }
    val kuboProcessTest by tasks.registering(Test::class) {
        description = "Runs the owned Kubo process tests on the supplied JDK."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        executable = file(kuboJavaHome.get()).resolve("bin/java.exe").absolutePath
        useJUnitPlatform()
        include("io/libp2p/transport/quic/QuicKuboTestJava.class")
        dependsOn(prepareKubo)
        systemProperty("libp2p.test.kubo.executable", kuboExecutable.get().asFile.absolutePath)
        testLogging {
            events("FAILED")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    tasks.named("check") { dependsOn(kuboProcessTest) }
}
