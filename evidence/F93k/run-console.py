import json
import hashlib
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

lane = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
label, overlay, *classes = sys.argv[1:]
output = evidence / label
output.mkdir(parents=True, exist_ok=True)
classpath = (lane / 'build-cp/runtime-classpath').read_text().strip()
if overlay != '-':
    classpath = str(lane / overlay) + ':' + classpath
command = [str(evidence / 'with-slots.sh'), '2', 'timeout', '600',
           '/opt/java/bin/java', '-jar', str(lane / 'junit-console.jar'),
           'execute', '--disable-banner', '--details=summary',
           '--class-path', classpath, '--reports-dir', str(output)]
for name in classes:
    command += ['--select-class', 'io.libp2p.transport.' + name]
with (evidence / (label + '.log')).open('w') as log:
    result = subprocess.run(command, cwd=lane / 'jvm-libp2p', stdout=log, stderr=subprocess.STDOUT)
cases = []
for xml in sorted(output.glob('*.xml')):
    tree = ET.parse(xml)
    for case in tree.findall('.//testcase'):
        failure = case.find('failure')
        if failure is None:
            failure = case.find('error')
        cases.append(dict(name=case.get('name'), classname=case.get('classname'),
                          passed=failure is None and case.find('skipped') is None,
                          skipped=case.find('skipped') is not None, attempts=1,
                          failure=None if failure is None else failure.get('message')))
    for parent in tree.iter():
        for child in list(parent):
            if child.tag in ('properties', 'system-out', 'system-err'):
                parent.remove(child)
    tree.write(xml, encoding='utf-8', xml_declaration=True)
summary = dict(exit=result.returncode, passed=sum(c['passed'] for c in cases),
               total=len(cases), skipped=sum(c['skipped'] for c in cases),
               retries=[], cases=cases)
summary['test_sources'] = {name: hashlib.sha256(
    (lane / 'jvm-libp2p/libp2p/src/test/kotlin/io/libp2p/transport' / (name + '.kt')).read_bytes()
).hexdigest() for name in classes}
summary['product_head'] = subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], cwd=lane / 'jvm-libp2p', text=True).strip()
summary['overlay'] = overlay
(evidence / (label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({key: summary[key] for key in ('exit', 'passed', 'total', 'skipped', 'retries')}))
sys.exit(result.returncode)
