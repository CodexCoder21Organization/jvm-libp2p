from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
workspace = evidence.parents[2]
label = sys.argv[1]
repo = workspace / label
raw = workspace / (label + '-raw')
raw.mkdir(exist_ok=True)
paths = (evidence / 'touched-paths.txt').read_text().splitlines()
classes = ['io.libp2p.transport.' + Path(p).stem for p in paths if p.endswith('.kt')]
command = [str(evidence / 'with-slots.sh'), '2', 'timeout', '1200', str(repo / 'gradlew'),
           '--no-daemon', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process',
           '-I', str(evidence / 'gradle-control.gradle'), '-Dr93t.xml=' + str(raw), ':libp2p:test']
for name in classes:
    command.extend(['--tests', name])
inputs = {p: hashlib.sha256((repo / p).read_bytes()).hexdigest() for p in paths}
(evidence / (label + '-inputs.json')).write_text(json.dumps(inputs, indent=2))
with (evidence / (label + '-tests.log')).open('w') as log:
    process = subprocess.Popen(command, cwd=repo, env=dict(os.environ, JAVA_HOME='/opt/java'), stdout=log, stderr=subprocess.STDOUT)
    (evidence / (label + '-pid.json')).write_text(json.dumps({'pid': process.pid}))
    code = process.wait()
output = evidence / (label + '-xml')
output.mkdir(exist_ok=True)
cases = []
for path in sorted(raw.glob('*.xml')):
    tree = ET.parse(path)
    for case in tree.findall('.//testcase'):
        failures = case.findall('failure') + case.findall('error')
        cases.append({'class': case.get('classname'), 'name': case.get('name'),
                      'passed': not failures and case.find('skipped') is None,
                      'skipped': case.find('skipped') is not None,
                      'retry_entries': len(case.findall('rerunFailure')) + len(case.findall('flakyFailure')),
                      'failures': [{'type': f.get('type'), 'message': f.get('message'), 'trace': f.text} for f in failures]})
    for node in tree.iter():
        for child in list(node):
            if child.tag in ('properties', 'system-out', 'system-err'):
                node.remove(child)
    tree.write(output / path.name, encoding='utf-8', xml_declaration=True)
summary = {'exit': code, 'passed': sum(c['passed'] for c in cases), 'total': len(cases),
           'skips': sum(c['skipped'] for c in cases), 'retry_entries': sum(c['retry_entries'] for c in cases), 'cases': cases}
(evidence / (label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({k:v for k,v in summary.items() if k != 'cases'}))
raise SystemExit(code)
