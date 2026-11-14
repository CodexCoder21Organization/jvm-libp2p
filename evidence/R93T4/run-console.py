import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
label, overlay, *classes = sys.argv[1:]
output = evidence / label
output.mkdir(parents=True, exist_ok=True)
classpath = str(lane / 'jctools.jar') + ':' + (lane / 'build-cp/runtime-classpath').read_text().strip()
if overlay != '-':
    classpath = ':'.join(str(lane / p) for p in overlay.split(':')) + ':' + classpath
command = [str(evidence / 'with-slots.sh'), '1', 'timeout', '600',
           '/opt/java/bin/java', '-jar', str(lane / 'junit-console.jar'),
           'execute', '--disable-banner', '--details=summary', '--class-path', classpath,
           '--reports-dir', str(output)]
for name in classes:
    command += ['--select-method' if '#' in name else '--select-class', 'io.libp2p.transport.' + name]
with (lane / (label + '.log')).open('w') as log:
    result = subprocess.run(command, cwd=lane / 'head', stdout=log, stderr=subprocess.STDOUT)
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
               total=len(cases), skipped=sum(c['skipped'] for c in cases), retries=[], cases=cases)
summary['test_sources'] = {name: hashlib.sha256((lane / 'head/libp2p/src/test/kotlin/io/libp2p/transport' / (name.split('#')[0]+'.kt')).read_bytes()).hexdigest() for name in classes}
summary['product_head'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=lane / 'head', text=True).strip()
summary['overlay'] = overlay
(evidence / (label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({k: summary[k] for k in ('exit', 'passed', 'total', 'skipped', 'retries')}))
sys.exit(result.returncode)
