#!/usr/bin/env python3
"""Build and verify the repository's generated API documentation."""

import argparse
import json
import os
import re
import shutil
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


DEADLINE_SECONDS = 1200
MAX_METASPACE_BYTES = 512 * 1024 * 1024
EXPECTED_KOTLIN_STDLIB = "1.6.21"
MODULES = [
    "libp2p",
    "interop-test-client",
    "tools/schedulers",
    "tools/simulator",
    "examples/chatter",
    "examples/cli-chatter",
    "examples/pinger",
]
OOM_PATTERN = re.compile(r"(?:java\.lang\.)?OutOfMemoryError", re.IGNORECASE)
SERVICE_CLOSE_PATTERN = re.compile(
    r"(?:could not|failed to|unable to|error while|exception while)\s+"
    r"(?:stop|stopping|shut\s*down|close|closing)\s+"
    r"(?:all\s+)?(?:Gradle\s+)?services?",
    re.IGNORECASE,
)
KOTLIN_METADATA_ERRORS = (
    "Module was compiled with an incompatible version of Kotlin",
    "compiled with an incompatible version of Kotlin",
    "compiled with a newer version of Kotlin",
    "The binary version of its metadata is",
)
ANSI_ESCAPE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


class Verification:
    def __init__(self):
        self.suite_started = time.monotonic()
        self.suite = ET.Element("testsuite", name="DocumentationContractTest")
        self.case_count = 0
        self.failure_count = 0

    def case(self, name, action):
        self.case_count += 1
        case_started = time.monotonic()
        element = ET.SubElement(
            self.suite,
            "testcase",
            classname="DocumentationContractTest",
            name=name,
        )
        try:
            detail = action()
            if detail:
                print(f"PASS {name}: {detail}", flush=True)
            else:
                print(f"PASS {name}", flush=True)
            return True, detail
        except BaseException:
            self.failure_count += 1
            detail = traceback.format_exc()
            failure = ET.SubElement(element, "failure", type="AssertionError")
            failure.text = detail
            print(f"FAIL {name}\n{detail}", flush=True)
            return False, detail
        finally:
            element.set("time", f"{time.monotonic() - case_started:.3f}")

    def write_xml(self, path: Path):
        self.suite.set("tests", str(self.case_count))
        self.suite.set("failures", str(self.failure_count))
        self.suite.set("time", f"{time.monotonic() - self.suite_started:.3f}")
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(self.suite).write(path, encoding="utf-8", xml_declaration=True)


def remaining_seconds(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError(f"The {DEADLINE_SECONDS}-second documentation verification deadline expired")
    return remaining


def signal_process_group(process: subprocess.Popen, signal_number: int):
    if os.name == "posix":
        try:
            os.killpg(process.pid, signal_number)
        except ProcessLookupError:
            pass
    elif process.poll() is None:
        if signal_number == signal.SIGTERM:
            process.terminate()
        else:
            process.kill()


def process_group_exists(process: subprocess.Popen) -> bool:
    if os.name != "posix":
        return process.poll() is None
    try:
        os.killpg(process.pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def terminate_process_group(process: subprocess.Popen):
    signal_process_group(process, signal.SIGTERM)
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass

    group_deadline = time.monotonic() + 2
    while process_group_exists(process) and time.monotonic() < group_deadline:
        time.sleep(0.1)

    if process_group_exists(process):
        signal_process_group(process, signal.SIGKILL)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        signal_process_group(process, signal.SIGKILL)
        process.wait(timeout=5)


def wait_for_gradle(process: subprocess.Popen, timeout: float):
    try:
        return process.wait(timeout=timeout), False
    except subprocess.TimeoutExpired:
        terminate_process_group(process)
        return process.returncode, True
    except BaseException:
        terminate_process_group(process)
        raise


def start_new_session_options():
    return {"start_new_session": True} if os.name == "posix" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}


def command_text(command) -> str:
    return subprocess.list2cmdline(command) if os.name == "nt" else shlex.join(command)


def run_build(command, checkout: Path, timeout: float, output):
    process = subprocess.Popen(
        command,
        cwd=checkout,
        stdout=output,
        stderr=subprocess.STDOUT,
        **start_new_session_options(),
    )
    return wait_for_gradle(process, timeout)


def replace_owned_report(output_directory: Path, source_directory: Path):
    output_directory.mkdir(parents=True, exist_ok=True)
    for name in ("gradle.log", "TEST-DocumentationContractTest.xml", "profiles", "runtime-stdlib.json"):
        target = output_directory / name
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
    for item in source_directory.iterdir():
        destination = output_directory / item.name
        if item.is_dir():
            shutil.copytree(item, destination, dirs_exist_ok=True)
        elif item.is_file():
            shutil.copy2(item, destination)


def normalized_log(path: Path) -> str:
    if not path.exists():
        return ""
    return ANSI_ESCAPE.sub("", path.read_text(encoding="utf-8", errors="replace"))


def parse_size(value: str):
    match = re.fullmatch(r"(?i)([0-9]+)([kmgt]?)", value.strip())
    if not match:
        return None
    amount = int(match.group(1))
    unit = match.group(2).lower()
    multiplier = {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3, "t": 1024**4}[unit]
    return amount * multiplier


def parse_worker_metaspace_cap(command_line: str):
    match = re.search(r"(?i)(?:^|\s)-XX:MaxMetaspaceSize=([^\s]+)", command_line)
    return parse_size(match.group(1)) if match else None


def require(condition: bool, message: str):
    if not condition:
        raise AssertionError(message)


def main() -> int:
    checkout = Path(__file__).resolve().parents[1]

    def handle_termination(signum, _frame):
        raise KeyboardInterrupt(f"received signal {signum}; stopping the owned Gradle process group")

    if os.name == "posix":
        signal.signal(signal.SIGTERM, handle_termination)

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=checkout / "build" / "documentation-verification",
        help="directory for gradle.log, JUnit XML, and JVM profiles",
    )
    arguments = parser.parse_args()
    output_directory = arguments.output.resolve()
    deadline = time.monotonic() + DEADLINE_SECONDS
    verifier = None
    build_result = {"return_code": None, "timed_out": False}

    with tempfile.TemporaryDirectory(prefix="documentation-verification-") as temporary_directory:
        temporary_root = Path(temporary_directory)
        work_reports = temporary_root / "reports"
        work_reports.mkdir(parents=True)
        gradle_log = work_reports / "gradle.log"
        verifier = Verification()

        def build_documentation():
            init_script = checkout / "scripts" / "documentation-profile.gradle"
            command = [
                "./gradlew",
                "--no-daemon",
                "--stacktrace",
                "--continue",
                "--parallel",
                "-I",
                str(init_script),
                f"-PdocumentationVerificationOutput={work_reports}",
                "clean",
                "assemble",
                "spotlessCheck",
                "testClasses",
                "dokkaJar",
                "dokkaHtml",
                ":examples:android-chatter:assembleDebug",
                "verifyDocumentationRuntimeStdlib",
            ]
            with gradle_log.open("w", encoding="utf-8") as log:
                log.write("=== COLD DOCUMENTATION CONTRACT BUILD ===\n")
                log.write(f"COMMAND: {command_text(command)}\n\n")
                log.flush()
                build_result["return_code"], build_result["timed_out"] = run_build(
                    command, checkout, remaining_seconds(deadline), log
                )
            build_result["finished"] = not build_result["timed_out"]
            require(
                build_result["finished"] and build_result["return_code"] == 0,
                f"The complete Gradle documentation contract build must exit 0 before the "
                f"{DEADLINE_SECONDS}-second deadline; exit={build_result['return_code']}, "
                f"timed_out={build_result['timed_out']}. See gradle.log for the complete output.",
            )
            log = normalized_log(gradle_log)
            require("BUILD SUCCESSFUL" in log,
                    "The contract Gradle invocation exited 0 without a complete BUILD SUCCESSFUL shutdown summary")
            return "clean, assemble, spotlessCheck, testClasses, Dokka HTML/Javadoc, and Android assembleDebug completed in one invocation"

        verifier.case("combinedBuildAndShutdown", build_documentation)

        def inspect_gradle_log():
            log = normalized_log(gradle_log)
            require(gradle_log.exists(), "Gradle output log was not created")
            oom_matches = [line for line in log.splitlines() if OOM_PATTERN.search(line)]
            require(not oom_matches, "Gradle output contains an OutOfMemoryError: " + "\n".join(oom_matches))
            service_errors = [line for line in log.splitlines() if SERVICE_CLOSE_PATTERN.search(line)]
            require(not service_errors, "Gradle output contains a service shutdown error: " + "\n".join(service_errors))
            metadata_errors = [needle for needle in KOTLIN_METADATA_ERRORS if needle in log]
            require(not metadata_errors, "Gradle output contains an incompatible Kotlin metadata diagnostic: " + repr(metadata_errors))
            return "no OutOfMemoryError, service shutdown error, or incompatible Kotlin metadata diagnostic"

        verifier.case("completeLogHasNoMemoryOrShutdownErrors", inspect_gradle_log)

        profiles_directory = work_reports / "profiles"
        ledger = profiles_directory / "tasks.jsonl"
        profile_entries = []

        def load_profiles():
            require(ledger.is_file(), f"Dokka profiling ledger is missing: {ledger}")
            lines = ledger.read_text(encoding="utf-8", errors="replace").splitlines()
            require(bool(lines), "Dokka profiling ledger contains no task records")
            try:
                profile_entries.extend(json.loads(line) for line in lines if line.strip())
            except json.JSONDecodeError as error:
                raise AssertionError(f"Dokka profiling ledger contains invalid JSON: {error}") from error
            finished = [entry for entry in profile_entries if entry.get("phase") == "after"]
            require(bool(finished), "No documentation generation task reached its after-task profile")
            task_names = [entry.get("task", "").lower() for entry in finished]
            require(any("html" in name for name in task_names), f"No HTML generation task was profiled: {task_names}")
            require(any("javadoc" in name for name in task_names), f"No Javadoc generation task was profiled: {task_names}")
            failed_tasks = [(entry.get("task"), entry.get("failure")) for entry in finished if entry.get("failure")]
            require(not failed_tasks, f"Dokka generation reported failed tasks: {failed_tasks}")
            return f"recorded {len(finished)} completed documentation generation task profiles"

        verifier.case("documentationTasksWereProfiled", load_profiles)

        def check_aggregate_metaspace_budget():
            finished = [entry for entry in profile_entries if entry.get("phase") == "after"]
            require(bool(finished), "Cannot check process caps without completed Dokka profile records")
            root_pids = {entry.get("rootPid") for entry in finished}
            require(len(root_pids) == 1 and None not in root_pids, f"Expected one recorded Gradle root JVM, found {root_pids}")
            root_caps = [entry.get("rootMetaspaceMaxBytes") for entry in finished]
            require(all(isinstance(cap, int) and cap > 0 for cap in root_caps), f"Invalid Gradle root Metaspace caps: {root_caps}")
            root_cap = max(root_caps)

            worker_caps = {}
            for entry in finished:
                for worker in entry.get("workers", []):
                    pid = worker.get("pid")
                    command_line = worker.get("commandLine") or ""
                    require(pid is not None and "GradleWorkerMain" in command_line,
                            f"Profiled documentation worker is not a GradleWorkerMain JVM: {worker}")
                    cap = parse_worker_metaspace_cap(command_line)
                    require(cap is not None and cap > 0, f"Could not parse a configured worker MaxMetaspaceSize from jcmd: {worker}")
                    recorded_cap = worker.get("maxMetaspaceBytes")
                    require(recorded_cap == cap, f"Worker cap field differs from jcmd VM.command_line for pid {pid}: {recorded_cap} != {cap}")
                    if pid in worker_caps:
                        require(worker_caps[pid] == cap, f"Worker pid {pid} reported different Metaspace caps: {worker_caps[pid]} and {cap}")
                    worker_caps[pid] = cap

            total_cap = root_cap + sum(worker_caps.values())
            cap_report = {
                "rootPid": next(iter(root_pids)),
                "rootMetaspaceCapBytes": root_cap,
                "workerMetaspaceCapsBytes": worker_caps,
                "totalRetainedMetaspaceCapBytes": total_cap,
                "maximumBytes": MAX_METASPACE_BYTES,
            }
            print("Metaspace cap accounting:", json.dumps(cap_report, sort_keys=True), flush=True)
            require(
                total_cap <= MAX_METASPACE_BYTES,
                f"Gradle root cap plus all unique retained Dokka worker caps must be at most "
                f"512 MiB; observed {total_cap} bytes: {json.dumps(cap_report, sort_keys=True)}",
            )
            return f"root plus {len(worker_caps)} unique retained Dokka workers cap at {total_cap} bytes"

        verifier.case("aggregateRootAndDokkaWorkerMetaspaceBudget", check_aggregate_metaspace_budget)

        def verify_runtime_stdlib():
            report_path = work_reports / "runtime-stdlib.json"
            require(report_path.is_file(), f"The runtime stdlib version task did not write {report_path}")
            report = json.loads(report_path.read_text(encoding="utf-8"))
            require(report.get("expectedVersion") == EXPECTED_KOTLIN_STDLIB,
                    f"Unexpected expectedVersion in runtime stdlib report: {report}")
            components = report.get("components", [])
            require(bool(components), "No org.jetbrains.kotlin:kotlin-stdlib* modules were found on runtime classpaths")
            wrong = [component for component in components if component.get("version") != EXPECTED_KOTLIN_STDLIB]
            require(not wrong, f"Expected Kotlin stdlib {EXPECTED_KOTLIN_STDLIB} on runtime classpaths, found {wrong}")
            inspected = set(report.get("inspectedConfigurations", []))
            required = {
                ":libp2p:runtimeClasspath",
                ":examples:android-chatter:debugRuntimeClasspath",
                ":examples:android-chatter:releaseRuntimeClasspath",
            }
            require(required <= inspected,
                    f"Runtime classpath report omitted required libp2p/Android configurations: {required - inspected}")
            configurations_with_stdlib = {
                f"{component.get('project')}:{component.get('configuration')}" for component in components
            }
            require(required <= configurations_with_stdlib,
                    f"Kotlin stdlib components are missing from required runtime classpaths: "
                    f"{required - configurations_with_stdlib}")
            return f"{len(components)} resolved stdlib component records use {EXPECTED_KOTLIN_STDLIB}; libp2p and Android debug/release included"

        verifier.case("runtimeClasspathPinsKotlinStdlib", verify_runtime_stdlib)

        def verify_html_outputs():
            checked = []
            for module in MODULES:
                output = checkout / module / "build" / "dokka"
                index = output / "index.html"
                require(index.is_file() and index.stat().st_size > 0,
                        f"{module}: expected a nonempty HTML documentation index at {index}")
                content = [
                    path for path in output.rglob("*")
                    if path.is_file() and path != index and path.stat().st_size > 0
                ]
                require(content, f"{module}: HTML output contains no nonempty content besides index.html in {output}")
                checked.append(f"{module} ({len(content)} content files)")
            host_pages = list((checkout / "libp2p" / "build" / "dokka").rglob("-host/index.html"))
            require(len(host_pages) == 1, f"Expected one HTML Host API page, found {host_pages}")
            host_content = host_pages[0].read_text(encoding="utf-8")
            require("new-stream" in host_content or "newStream" in host_content,
                    f"HTML Host page has no newStream API documentation: {host_pages[0]}")
            return "; ".join(checked)

        verifier.case("allSourceModulesHaveHtmlContent", verify_html_outputs)

        def verify_javadoc_archive_and_api_page():
            checked = []
            host_page_text = None
            for module in MODULES:
                libraries = list((checkout / module / "build" / "libs").glob("*-javadoc.jar"))
                require(len(libraries) == 1,
                        f"{module}: expected exactly one Javadoc jar, found {[str(path) for path in libraries]}")
                with zipfile.ZipFile(libraries[0]) as archive:
                    names = set(archive.namelist())
                    require("index.html" in names, f"{module}: Javadoc archive {libraries[0]} has no index.html")
                    require(archive.getinfo("index.html").file_size > 0,
                            f"{module}: Javadoc index in {libraries[0]} is empty")
                    require(any(name.endswith(".html") and name != "index.html" and archive.getinfo(name).file_size > 0
                                for name in names),
                            f"{module}: Javadoc archive {libraries[0]} has no nonempty API page besides index.html")
                    if module == "libp2p":
                        host_path = "io/libp2p/core/Host.html"
                        require(host_path in names, f"libp2p: expected representative public API page {host_path} in {libraries[0]}")
                        host_page_text = archive.read(host_path).decode("utf-8", errors="replace")
                        require("newStream" in host_page_text and "start" in host_page_text,
                                f"{host_path} does not contain expected public Host API methods newStream and start")
                checked.append(str(libraries[0].relative_to(checkout)))
            return "; ".join(checked)

        verifier.case("javadocArchivesContainPublicApiPages", verify_javadoc_archive_and_api_page)

        def verify_host_bytecode_metadata():
            class_file = checkout / "libp2p" / "build" / "classes" / "kotlin" / "main" / "io" / "libp2p" / "core" / "Host.class"
            require(class_file.is_file(), f"Compiled public API class is missing: {class_file}")
            java_home = os.environ.get("JAVA_HOME")
            javap_name = "javap.exe" if os.name == "nt" else "javap"
            javap = shutil.which(javap_name)
            if javap is None and java_home:
                candidate = Path(java_home) / "bin" / javap_name
                if candidate.is_file():
                    javap = str(candidate)
            require(javap is not None, "Could not find javap from the active JDK")
            try:
                result = subprocess.run(
                    [javap, "-v", str(class_file)],
                    cwd=checkout,
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=20,
                )
            except subprocess.TimeoutExpired as error:
                raise AssertionError("javap did not inspect Host.class within 20 seconds") from error
            output = result.stdout + result.stderr
            require(result.returncode == 0, f"javap -v failed for {class_file}:\n{output}")
            require("major version: 55" in output,
                    f"Host.class must use JVM bytecode version 55, but javap reported:\n{output}")
            require(re.search(r"kotlin\.Metadata\s*\([\s\S]*?mv=\[1,\s*6,\s*0\]", output) is not None,
                    f"Host.class Kotlin metadata must be mv=[1,6,0], but javap reported:\n{output}")
            return "Host.class uses JVM 55 and Kotlin metadata mv=[1,6,0]"

        verifier.case("hostBytecodeAndKotlinMetadataMatchPublishedContract", verify_host_bytecode_metadata)

        verifier.write_xml(work_reports / "TEST-DocumentationContractTest.xml")
        replace_owned_report(output_directory, work_reports)

    print(f"Documentation verification reports: {output_directory}", flush=True)
    print(f"JUnit: {output_directory / 'TEST-DocumentationContractTest.xml'}", flush=True)
    return 0 if verifier is not None and verifier.failure_count == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
