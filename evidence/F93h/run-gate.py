from pathlib import Path
import subprocess, sys, time, json, xml.etree.ElementTree as ET

e = Path(__file__).resolve().parent
lane = e.parents[2]
build = lane / 'f93h-build'
name, repeats, *selectors = sys.argv[1:]
if selectors == ['--pr']:
    selectors = json.loads((e / 'pr-selectors.json').read_text())
classpath = ':'.join([str(build / 'overlay'), str(build / 'gate'), '/tmp/F93h-junit-console.jar', (build / 'runtime-classpath').read_text()])
command = ['/opt/java/bin/java', '-ea', '-cp', classpath, 'F93dGate', str(e / name), repeats] + selectors
started = time.monotonic()
with (e / (name + '.log')).open('w') as output:
    process = subprocess.Popen([str(e / 'with-slots.sh'), '2', 'timeout', '1200'] + command, cwd=lane / 'jvm-libp2p', stdout=output, stderr=subprocess.STDOUT)
    (e / (name + '.pid')).write_text(str(process.pid))
    code = process.wait()
    (e / (name + '.pid')).unlink()
summary = dict(exit=code, seconds=round(time.monotonic() - started, 3), attempts=0, passed=0, failed=0, skipped=0, retry_elements=[], failures=[])
for xml in (e / name).rglob('*.xml'):
    tree = ET.parse(xml)
    for node in tree.iter():
        for child in list(node):
            if child.tag == 'properties':
                node.remove(child)
    for case in tree.iter('testcase'):
        summary['attempts'] += 1
        failure = case.find('failure')
        error = case.find('error')
        if case.find('skipped') is not None:
            summary['skipped'] += 1
        elif failure is not None or error is not None:
            summary['failed'] += 1
            issue = failure if failure is not None else error
            summary['failures'].append(dict(classname=case.get('classname'), name=case.get('name'), message=issue.get('message')))
        else:
            summary['passed'] += 1
    summary['retry_elements'] += [n.tag for n in tree.iter() if any(w in n.tag.lower() for w in ['retry', 'rerun', 'flaky'])]
    tree.write(xml, encoding='unicode')
(e / (name + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({k: v for k, v in summary.items() if k != 'failures'}), flush=True)
sys.exit(code)
