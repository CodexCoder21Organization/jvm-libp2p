#!/usr/bin/env python3
"""Prepare the remote native-suite bridge after build service allocation is available."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--launcher", type=Path, required=True, help="Path to UrlProtocol/scripts/test.bash")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
with zipfile.ZipFile(root / "f71-native-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for name in filter(None, tracked):
        archive.writestr(name, subprocess.check_output(["git", "show", "HEAD:" + name], cwd=root))
(root / "scripts").mkdir(exist_ok=True)
shutil.copyfile(args.launcher, root / "scripts/test.bash")
(root / "scripts/test.bash").chmod(0o755)
(root / "tests").mkdir(exist_ok=True)
(root / "build.kts").write_text('@KotlinBuildScript("https://tools.kotlin.build/")\n@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")\npackage lane.fseventyone\nimport build.kotlin.withartifact.WithArtifact\nimport java.io.File\nimport java.nio.file.Files\nimport java.util.zip.ZipEntry\nimport java.util.zip.ZipInputStream\nimport java.util.zip.ZipOutputStream\n\nfun librarySuite(): File {\n    val directory = Files.createTempDirectory("f71-native-").toFile()\n    val artifact = File.createTempFile("f71-native-results-", ".jar")\n    try {\n        ZipInputStream(File(__FILE__).parentFile.resolve("f71-native-source.zip").inputStream()).use { input ->\n            while (true) {\n                val entry = input.nextEntry ?: break\n                val file = directory.resolve(entry.name)\n                if (entry.isDirectory) file.mkdirs() else {\n                    file.parentFile.mkdirs()\n                    file.outputStream().use { input.copyTo(it) }\n                }\n            }\n        }\n        check(directory.resolve("gradlew").setExecutable(true))\n        val process = ProcessBuilder("./gradlew", "--no-daemon", ":libp2p:test", ":libp2p:spotlessCheck", ":libp2p:detekt")\n            .directory(directory).redirectErrorStream(true).start()\n        process.inputStream.bufferedReader().use { input -> input.forEachLine { println(it) } }\n        check(process.waitFor() == 0) { "The complete native library suite did not pass; see the Gradle output." }\n        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()\n        val files = directory.resolve("libp2p/build/test-results/test").listFiles()?.filter { it.extension == "xml" } ?: emptyList()\n        var count = 0\n        files.forEach {\n            val root = factory.newDocumentBuilder().parse(it).documentElement\n            count += root.getAttribute("tests").toInt()\n            check(root.getAttribute("failures") == "0" && root.getAttribute("errors") == "0")\n        }\n        check(count > 0)\n        ZipOutputStream(artifact.outputStream()).use { output ->\n            output.putNextEntry(ZipEntry("f71-native-suite.properties"))\n            output.write("tests=$count\\nfiles=${files.size}\\n".toByteArray())\n            output.closeEntry()\n            files.forEach { file ->\n                output.putNextEntry(ZipEntry("f71-native-results/${file.name}"))\n                file.inputStream().use { it.copyTo(output) }\n                output.closeEntry()\n            }\n        }\n        println("F71 native suite: $count checks passed on one Gradle execution.")\n        return artifact\n    } finally {\n        directory.deleteRecursively()\n    }\n}\n')
(root / "tests/testLibraryCompleteSuite.kts").write_text('@file:WithArtifact("lane.fseventyone.librarySuite()")\n@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")\nimport build.kotlin.withartifact.WithArtifact\nfun testLibraryCompleteSuite() {\n    val properties = java.util.Properties()\n    val input = Thread.currentThread().contextClassLoader.getResourceAsStream("f71-native-suite.properties")\n        ?: error("The native suite summary was not included in the classpath.")\n    input.use { properties.load(it) }\n    check(properties.getProperty("tests").toInt() > 0)\n    println("F71 native suite: ${properties.getProperty("tests")} checks passed on one Gradle execution.")\n}\n')
print("Prepared scripts/test.bash --remote --test . --log F71-library-suite.xml")
