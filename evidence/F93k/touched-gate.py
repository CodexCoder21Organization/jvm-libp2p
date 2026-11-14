from pathlib import Path
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
product = lane / 'jvm-libp2p'
environment = os.environ.copy()
environment['JAVA_HOME'] = '/opt/java'
base = ['./gradlew', '--no-daemon', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process']
with (evidence / 'touched-compile.log').open('w') as log:
    compiled = subprocess.run([str(evidence / 'with-slots-v2.sh'), '1', 'timeout', '600'] + base +
                              [':libp2p:compileTestKotlin', ':libp2p:compileTestJava', ':libp2p:spotlessCheck', ':libp2p:detekt'],
                              cwd=product, env=environment, stdout=log, stderr=subprocess.STDOUT)
if compiled.returncode:
    print('OBSERVED touched compile/style exit=' + str(compiled.returncode))
    sys.exit(compiled.returncode)
paths = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop', 'HEAD'], cwd=product, text=True).splitlines()
classes = ['io.libp2p.transport.' + Path(path).stem for path in paths if '/src/test/' in path and path.endswith('.kt')]
(evidence / 'touched-classes.json').write_text(json.dumps(classes, indent=2))
xml_output = evidence / 'touched-class-xml'
arguments = base + [':libp2p:test', '-I', str(evidence / 'control.gradle'), '-Dr93t.xml=' + str(xml_output)]
for name in classes:
    arguments += ['--tests', name]
with (evidence / 'touched-test.log').open('w') as log:
    tested = subprocess.run([str(evidence / 'with-slots-v2.sh'), '2', 'timeout', '900'] + arguments,
                            cwd=product, env=environment, stdout=log, stderr=subprocess.STDOUT)
cases = []
for xml in sorted(xml_output.glob('*.xml')):
    tree = ET.parse(xml)
    for case in tree.findall('.//testcase'):
        tags = [child.tag for child in case]
        cases.append(dict(classname=case.get('classname'), name=case.get('name'),
                          failed='failure' in tags or 'error' in tags, skipped='skipped' in tags,
                          retry_tags=[tag for tag in tags if 'retry' in tag.lower() or 'rerun' in tag.lower() or 'flaky' in tag.lower()]))
    for parent in tree.iter():
        for child in list(parent):
            if child.tag in ('properties', 'system-out', 'system-err'):
                parent.remove(child)
    tree.write(xml, encoding='utf-8', xml_declaration=True)
summary = dict(head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=product, text=True).strip(),
               exit=tested.returncode, classes=len(classes), total=len(cases),
               passed=sum(not c['failed'] and not c['skipped'] for c in cases),
               failed=sum(c['failed'] for c in cases), skipped=sum(c['skipped'] for c in cases),
               retries=[c for c in cases if c['retry_tags']], cases=cases)
(evidence / 'touched-summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps({key: summary[key] for key in ('head', 'exit', 'classes', 'total', 'passed', 'failed', 'skipped', 'retries')}))
sys.exit(tested.returncode if tested.returncode else (0 if len(cases) == 60 else 2))
