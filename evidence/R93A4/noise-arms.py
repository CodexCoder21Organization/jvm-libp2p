from pathlib import Path
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
arm, overlay = sys.argv[1:3]
classpath = (lane / 'build-cp/runtime-classpath').read_text().strip()
if overlay != '-':
    classpath = str(lane / overlay) + ':' + classpath
classpath += ':' + str(lane / 'junit-console.jar')
deadline = time.monotonic() + 1500
summary_path = evidence / ('noise-' + arm + '-summary.json')
results = json.loads(summary_path.read_text()) if summary_path.exists() else []
limit = min(10, len(results) + 1) if '--once' in sys.argv else 10
for index in range(len(results) + 1, limit + 1):
    output = evidence / ('noise-' + arm) / ('run-%02d' % index)
    output.mkdir(parents=True, exist_ok=True)
    wrapper = 'with-slots-set.sh'
    command = [str(evidence / wrapper), '3', 'timeout', '180',
               '/opt/java/bin/java', '-cp', classpath,
               'org.junit.platform.console.ConsoleLauncher', 'execute', '--disable-banner',
               '--details=summary', '--reports-dir', str(output),
               '--select-class', 'io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest']
    with (lane / ('noise-' + arm + '-%02d.log' % index)).open('w') as log:
        process = subprocess.Popen(command, cwd=lane / 'review', stdout=log, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=max(1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            process.terminate()
            code = process.wait(timeout=15)
    cases = []
    retry_entries = []
    for xml in sorted(output.glob('*.xml')):
        tree = ET.parse(xml)
        for case in tree.findall('.//testcase'):
            failure = case.find('failure')
            if failure is None:
                failure = case.find('error')
            metadata = case.findtext('system-out') or ''
            unique_id = next((line[len('unique-id: '):] for line in metadata.splitlines() if line.startswith('unique-id: ')), None)
            if unique_id is not None:
                case.set('uniqueId', unique_id)
            cases.append(dict(name=case.get('name'), classname=case.get('classname'), unique_id=unique_id,
                              passed=failure is None and case.find('skipped') is None,
                              skipped=case.find('skipped') is not None,
                              failure=None if failure is None else failure.get('message')))
            retry_entries.extend(child.tag for child in case
                                 if 'rerun' in child.tag.lower() or 'flaky' in child.tag.lower())
        for parent in tree.iter():
            for child in list(parent):
                if child.tag in ('properties', 'system-out', 'system-err'):
                    parent.remove(child)
        tree.write(xml, encoding='utf-8', xml_declaration=True)
    if code == 75 and not cases:
        print('OBSERVED: no slot set before deadline; body attempts=0', flush=True)
        sys.exit(75)
    result = dict(run=index, exit=code, total=len(cases), passed=sum(c['passed'] for c in cases),
                  skipped=sum(c['skipped'] for c in cases), retries=retry_entries, cases=cases)
    results.append(result)
    (evidence / ('noise-' + arm + '-summary.json')).write_text(json.dumps(results, indent=2))
    print(json.dumps({k: result[k] for k in ('run', 'exit', 'total', 'passed', 'skipped', 'retries')}), flush=True)
    if time.monotonic() >= deadline:
        break
