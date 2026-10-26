#!/usr/bin/env python3
"""Summarize the native Gradle XML returned by the V71 report artifact."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("archive", type=Path)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
totals = Counter()
case_occurrences = Counter()
issues = []
with zipfile.ZipFile(args.archive) as archive:
    properties = dict(line.split("=", 1) for line in archive.read("f71-native-suite.properties").decode().splitlines() if "=" in line)
    reports = [name for name in archive.namelist() if name.startswith("f71-native-results/") and name.endswith(".xml")]
    for name in reports:
        root = ET.fromstring(archive.read(name))
        for key in ("tests", "skipped", "errors", "failures"):
            totals[key] += int(root.attrib.get(key, "0"))
        for case in root.iter("testcase"):
            identity = case.attrib.get("classname", "") + "::" + case.attrib.get("name", "")
            case_occurrences[identity] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                issues.append(hashlib.sha256(identity.encode()).hexdigest())
            totals["additional_attempt_elements"] += len(case.findall("flakyFailure")) + len(case.findall("rerunFailure")) + len(case.findall("rerunError"))
    result = dict(totals)
    result.update(native_exit=int(properties["nativeExit"]), report_files=len(reports), passed=totals["tests"] - totals["skipped"] - totals["errors"] - totals["failures"], max_case_occurrences=max(case_occurrences.values(), default=0), issue_ids=issues, archive_sha256=hashlib.sha256(args.archive.read_bytes()).hexdigest())
args.output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, sort_keys=True))
