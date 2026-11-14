import csv
import json
from collections import Counter, defaultdict
from hashlib import sha256
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET

HERE = Path(__file__).parent
TMP = Path('/tmp/jvm-libp2p-actions-audit.QimSo5')
RUNS = {
    'run-43563290814.zip': '43563290814',
    'run-43563295235.zip': '43563295235',
    'run-43563295328.zip': '43563295328',
}

pr_files = json.loads((HERE / 'pr-files.json').read_text())['files']
test_paths = [f['path'] for f in pr_files if f['path'].startswith('libp2p/src/test/')]
changed_classes = {}
for path in test_paths:
    parts = Path(path).with_suffix('').parts
    marker = next((i for i, part in enumerate(parts) if part in {'kotlin', 'java'}), None)
    if marker is not None:
        changed_classes['.'.join(parts[marker + 1:])] = path

case_counts = Counter()
source_counts = defaultdict(Counter)
touched_counts = defaultdict(Counter)
touched_present = defaultdict(set)
run_hashes = {}
archive_summaries = []
canonical_by_run = {}
fullnames_by_run = {}
for archive in sorted(TMP.glob('run-*.zip')):
    run_id = RUNS[archive.name]
    archive_hash = sha256(archive.read_bytes()).hexdigest()
    run_hashes[run_id] = archive_hash
    per_run = Counter()
    fullnames = set()
    in_file_name_counts = defaultdict(Counter)
    with ZipFile(archive) as zf:
        for info in zf.infolist():
            path = info.filename
            if not path.lower().endswith('.xml'):
                continue
            if '/build/test-results/' in path:
                source = 'gradle-test'
            elif '/download-verification/' in path:
                source = 'download-verification'
            elif '/build/native-port-isolation-results/' in path:
                source = 'native-port-isolation'
            elif '/build/kubo-workflow-results/' in path:
                source = 'kubo-workflow'
            else:
                continue
            try:
                root = ET.fromstring(zf.read(info))
            except ET.ParseError:
                continue
            testcases = list(root.iter('testcase'))
            if not testcases:
                continue
            source_counts[(run_id, source)]['xml_files'] += 1
            for tc in testcases:
                classname = tc.get('classname') or ''
                name = tc.get('name') or ''
                full = f'{classname}::{name}'
                fullnames.add(full)
                in_file_name_counts[(source, path)][full] += 1
                skipped = tc.find('skipped') is not None
                failed = any(tc.find(tag) is not None for tag in ('failure', 'flakyFailure', 'rerunFailure'))
                errored = any(tc.find(tag) is not None for tag in ('error', 'flakyError', 'rerunError'))
                retry_events = sum(
                    elem.tag.rsplit('}', 1)[-1] in {'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'}
                    for elem in tc.iter()
                )
                outcome = 'skipped' if skipped else 'failed' if failed else 'errored' if errored else 'passed'
                key = (run_id, source, path, classname, name, outcome, 1, retry_events)
                case_counts[key] += 1
                per_run[(source, path, classname, name, outcome, retry_events)] += 1
                stats = source_counts[(run_id, source)]
                stats['testcase_records'] += 1
                stats['first_attempt_records'] += 1
                stats[outcome] += 1
                stats['explicit_retry_events'] += retry_events
                if retry_events:
                    stats['testcase_records_with_retry_markers'] += 1
                if source == 'gradle-test' and classname in changed_classes:
                    touched_present[(run_id, classname)].add(path)
                    t = touched_counts[(run_id, classname)]
                    t['testcase_records'] += 1
                    t[outcome] += 1
    duplicate_name_keys = sum(
        count > 1
        for files in [in_file_name_counts.values()]
        for file_counts in files
        for count in file_counts.values()
    )
    canonical_by_run[run_id] = per_run
    fullnames_by_run[run_id] = fullnames
    all_stats = Counter()
    for (rid, source), stats in list(source_counts.items()):
        if rid == run_id:
            for key in ('xml_files', 'testcase_records', 'first_attempt_records', 'passed', 'skipped', 'failed', 'errored', 'explicit_retry_events'):
                all_stats[key] += stats[key]
    archive_summaries.append((run_id, archive.name, archive.stat().st_size, archive_hash,
                              all_stats['testcase_records'], all_stats['passed'], all_stats['skipped'],
                              all_stats['failed'], all_stats['errored'], all_stats['explicit_retry_events'],
                              len(fullnames), duplicate_name_keys))

with (HERE / 'case-counts.csv').open('w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['run_id', 'result_source', 'result_path', 'class_name', 'testcase_name', 'outcome', 'testcase_record_count', 'first_attempt_records', 'explicit_retry_events'])
    for key, count in sorted(case_counts.items()):
        run_id, source, path, classname, name, outcome, _, retry_events = key
        w.writerow([run_id, source, path, classname, name, outcome, count, count, retry_events])
with (HERE / 'run-summary.csv').open('w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['run_id', 'source', 'xml_files', 'testcase_records', 'first_attempt_records', 'passed', 'skipped', 'failed', 'errored', 'explicit_retry_events'])
    for (run_id, source), stats in sorted(source_counts.items()):
        w.writerow([run_id, source] + [stats[k] for k in ('xml_files', 'testcase_records', 'first_attempt_records', 'passed', 'skipped', 'failed', 'errored', 'explicit_retry_events')])
    for summary in archive_summaries:
        run_id, _, _, _, cases, passed, skipped, failed, errored, retry, _, _ = summary
        xml_files = sum(source_counts[(run_id, src)]['xml_files'] for src in ('gradle-test','download-verification','native-port-isolation','kubo-workflow'))
        w.writerow([run_id, 'ALL_TEST_RESULT_XML', xml_files, cases, cases, passed, skipped, failed, errored, retry])
with (HERE / 'pr-touched-tests.csv').open('w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['run_id', 'changed_test_path', 'full_class_name', 'present_in_gradle_test_xml', 'testcase_records', 'passed', 'skipped', 'failed', 'errored'])
    for run_id in sorted(run_hashes):
        for classname, path in sorted(changed_classes.items()):
            stats = touched_counts[(run_id, classname)]
            w.writerow([run_id, path, classname, 'yes' if touched_present[(run_id, classname)] else 'no', stats['testcase_records'], stats['passed'], stats['skipped'], stats['failed'], stats['errored']])

print('PR changed test files:', len(test_paths), 'mapped classes:', len(changed_classes))
for summary in archive_summaries:
    run_id, archive_name, size, archive_hash, cases, passed, skipped, failed, errored, retries, unique_names, duplicates = summary
    print(f'{run_id}: archive={archive_name} size={size} sha256={archive_hash}; cases={cases} passed={passed} skipped={skipped} failed={failed} errored={errored} retry_events={retries}; unique_full_names={unique_names}; duplicate_name/file keys={duplicates}')
    for (rid, source), stats in sorted(source_counts.items()):
        if rid == run_id:
            print(f'  {source}: {stats["xml_files"]} xml, {stats["testcase_records"]} cases ({stats["passed"]} pass, {stats["skipped"]} skip, {stats["failed"]} fail, {stats["errored"]} error), retries={stats["explicit_retry_events"]}')
    represented = sum(bool(touched_present[(run_id, classname)]) for classname in changed_classes)
    touched_total = sum(touched_counts[(run_id, classname)]['testcase_records'] for classname in changed_classes)
    touched_pass = sum(touched_counts[(run_id, classname)]['passed'] for classname in changed_classes)
    touched_skip = sum(touched_counts[(run_id, classname)]['skipped'] for classname in changed_classes)
    print(f'  PR test classes in Gradle reports={represented}/{len(changed_classes)}; changed-class case records={touched_total}; pass={touched_pass}; skip={touched_skip}')
print('all three runs have equal case/source/path/outcome multisets:', canonical_by_run['43563290814'] == canonical_by_run['43563295235'] == canonical_by_run['43563295328'])
print('all three runs have equal full-name sets:', fullnames_by_run['43563290814'] == fullnames_by_run['43563295235'] == fullnames_by_run['43563295328'])
for run_id in sorted(run_hashes):
    skips = Counter()
    for (rid, source, path, classname, name, outcome, _, _), count in case_counts.items():
        if rid == run_id and outcome == 'skipped':
            skips[f'{classname}::{name}'] += count
    print(f'{run_id} skips:', [f'{name} x{count}' for name, count in sorted(skips.items())])
print('wrote case-counts.csv, run-summary.csv, pr-touched-tests.csv')
