#!/usr/bin/env python3
"""Prepare a self-contained remote run of the pinned native Gradle suite."""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--launcher", type=Path, required=True)
parser.add_argument("--revision", default="587aad5f1c7fda72a561f0f7bb09dfd8f63b8939")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", "-z", args.revision], cwd=root).decode().split("\0")
source = io.BytesIO()
with zipfile.ZipFile(source, "w") as archive:
    for name in filter(None, names):
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, subprocess.check_output(["git", "show", args.revision + ":" + name], cwd=root))
encoded = base64.b64encode(source.getvalue()).decode()
chunks = ",\n        ".join(json.dumps(encoded[i:i+60000]) for i in range(0, len(encoded), 60000))
(root / "scripts").mkdir(exist_ok=True)
shutil.copyfile(args.launcher, root / "scripts/test.bash")
(root / "scripts/test.bash").chmod(0o755)
(root / "tests").mkdir(exist_ok=True)

BUILD_TEMPLATE = '@KotlinBuildScript("https://tools.kotlin.build/")\n@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")\npackage lane.fseventyone\nimport build.kotlin.withartifact.WithArtifact\nimport java.io.File\nimport java.io.ByteArrayInputStream\nimport java.util.Base64\nimport java.nio.file.Files\nimport java.util.zip.ZipEntry\nimport java.util.zip.ZipInputStream\nimport java.util.zip.ZipOutputStream\n\nprivate fun nativeArchiveBytes(): ByteArray = Base64.getDecoder().decode(\n    listOf(__V71_ARCHIVE_CHUNKS__).joinToString("")\n)\n\nfun librarySuite(): File {\n    val directory = Files.createTempDirectory("f71-native-").toFile()\n    val artifact = File.createTempFile("f71-native-results-", ".jar")\n    try {\n        ZipInputStream(ByteArrayInputStream(nativeArchiveBytes())).use { input ->\n            while (true) {\n                val entry = input.nextEntry ?: break\n                val file = directory.resolve(entry.name)\n                if (entry.isDirectory) file.mkdirs() else {\n                    file.parentFile.mkdirs()\n                    file.outputStream().use { input.copyTo(it) }\n                }\n            }\n        }\n        check(directory.resolve("gradlew").setExecutable(true))\n        val process = ProcessBuilder("./gradlew", "--no-daemon", "--continue", "test")\n            .directory(directory).redirectErrorStream(true).start()\n        process.inputStream.bufferedReader().use { reader ->\n            directory.resolve("v71-native-gradle.log").bufferedWriter().use { log ->\n                reader.forEachLine { line -> log.appendLine(line) }\n            }\n        }\n        val nativeExit = process.waitFor()\n        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()\n        val files = directory.walkTopDown().filter { it.isFile && it.parentFile.name == "test" && it.parentFile.parentFile.name == "test-results" && it.extension == "xml" }.toList()\n        var count = 0\n        files.forEach {\n            val root = factory.newDocumentBuilder().parse(it).documentElement\n            count += root.getAttribute("tests").toInt()\n    \n        }\n    \n        ZipOutputStream(artifact.outputStream()).use { output ->\n            output.putNextEntry(ZipEntry("f71-native-suite.properties"))\n            output.write("tests=$count\\nfiles=${files.size}\\nnativeExit=$nativeExit\\nreports=${files.joinToString(";") { it.relativeTo(directory).invariantSeparatorsPath }}\\n".toByteArray())\n            output.closeEntry()\n            output.putNextEntry(ZipEntry("v71-native-gradle.log"))\n            directory.resolve("v71-native-gradle.log").inputStream().use { it.copyTo(output) }\n            output.closeEntry()\n            files.forEach { file ->\n                output.putNextEntry(ZipEntry("f71-native-results/${file.relativeTo(directory).invariantSeparatorsPath}"))\n                file.inputStream().use { it.copyTo(output) }\n                output.closeEntry()\n            }\n        }\n        println("F71 native suite: $count checks passed on one Gradle execution.")\n        return artifact\n    } finally {\n        directory.deleteRecursively()\n    }\n}\n'

READER_TEMPLATE = '@file:WithArtifact("lane.fseventyone.librarySuite()")\n@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")\nimport build.kotlin.withartifact.WithArtifact\nimport java.util.Properties\nfun testLibraryCompleteSuite() {\n    val input = Thread.currentThread().contextClassLoader.getResourceAsStream("f71-native-suite.properties")\n        ?: error("Native suite summary was not included in the classpath.")\n    val properties = Properties().also { p -> input.use { p.load(it) } }\n    check(properties.getProperty("tests").toInt() > 0) { "No native tests were recorded." }\n    check(properties.getProperty("nativeExit") == "0") { "Native Gradle tests did not complete successfully; inspect the native report artifact." }\n}\n'

(root / "build.kts").write_text(BUILD_TEMPLATE.replace("__V71_ARCHIVE_CHUNKS__", chunks))
(root / "tests/testLibraryCompleteSuite.kts").write_text(READER_TEMPLATE)
print("Source revision", args.revision)
print("Source archive SHA256", hashlib.sha256(source.getvalue()).hexdigest())
print("Prepared scripts/test.bash --remote --test . --log V71-library-suite-04.xml lane.fseventyone.librarySuite V71-native-reports.jar")
