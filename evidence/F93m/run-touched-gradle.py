from pathlib import Path
import json
import subprocess
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
repo = lane / 'jvm-libp2p'
files = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop', 'HEAD', '--', '*Test*'], cwd=repo, text=True).splitlines()
classes = ['io.libp2p.transport.' + Path(p).stem for p in files if p.endswith('.kt')]
command = [str(evidence / 'with-slots.sh'), '3', 'timeout', '1200', str(repo / 'gradlew'),
           '--no-daemon', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process',
           '-I', str(evidence / 'reused/gradle-control.gradle'),
           '-Dr93t.xml=' + str(lane / 'touched-gradle-raw'), ':libp2p:spotlessCheck', ':libp2p:test']
for name in classes:
    command += ['--tests', name]
(evidence / 'touched-classes.json').write_text(json.dumps(classes, indent=2))
with (lane / 'touched-gradle.log').open('w') as log:
    process = subprocess.Popen(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, env=dict(__import__('os').environ, JAVA_HOME='/opt/java'))
    (lane / 'touched-gradle.pid').write_text(str(process.pid))
    code = process.wait()
cases = []
output = evidence / 'touched-gradle'
output.mkdir(exist_ok=True)
for xml in sorted((lane / 'touched-gradle-raw').glob('*.xml')):
    tree = ET.parse(xml)
    for case in tree.findall('.//testcase'):
        cases.append(dict(name=case.get('name'), classname=case.get('classname'),
                          passed=case.find('failure') is None and case.find('error') is None and case.find('skipped') is None,
                          skipped=case.find('skipped') is not None,
                          retries=len(case.findall('rerunFailure')) + len(case.findall('flakyFailure'))))
    for parent in tree.iter():
        for child in list(parent):
            if child.tag in ('properties', 'system-out', 'system-err'):
                parent.remove(child)
    tree.write(output / xml.name, encoding='utf-8', xml_declaration=True)
summary = dict(exit=code, passed=sum(c['passed'] for c in cases), total=len(cases),
               skipped=sum(c['skipped'] for c in cases), retries=[c for c in cases if c['retries']], cases=cases)
(evidence / 'touched-gradle-summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps({k: summary[k] for k in ('exit', 'passed', 'total', 'skipped', 'retries')}))
raise SystemExit(code)
