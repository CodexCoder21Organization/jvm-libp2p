from pathlib import Path
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
product = lane / 'workspace/jvm-libp2p'
mode, label, *items = sys.argv[1:]
output = lane / 'overlays' / label
output.mkdir(parents=True, exist_ok=True)
runtime = (lane / 'build-cp/runtime-classpath').read_text().strip()
slot = str(evidence / 'with-slots.sh')
java = '/opt/java/bin/java'
if mode == 'compile':
    compiler = (lane / 'build-cp/compiler-classpath').read_text().strip()
    sources = [str(evidence / item) for item in items]
    command = [slot, '1', 'timeout', '600', java, '-cp', compiler,
               'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
               '-jvm-target', '11', '-language-version', '1.6', '-Xjvm-default=all',
               '-Xfriend-paths=' + str(product / 'libp2p/build/classes/kotlin/main'),
               '-classpath', runtime, '-d', str(output)] + sources
    inputs = {p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sources}
    (evidence / (label + '-inputs.json')).write_text(json.dumps(inputs, indent=2))
else:
    overlay, *classes = items
    classpath = runtime if overlay == '-' else str(lane / 'overlays' / overlay) + ':' + runtime
    reports = evidence / label
    reports.mkdir(exist_ok=True)
    command = [slot, '1', 'timeout', '600', java, '-jar', str(lane / 'junit-console.jar'),
               'execute', '--disable-banner', '--details=summary', '--class-path', classpath,
               '--reports-dir', str(reports)]
    for name in classes:
        command += ['--select-method' if '#' in name else '--select-class',
                    'io.libp2p.transport.' + name]
with (evidence / (label + '.log')).open('w') as log:
    result = subprocess.run(command, cwd=product, stdout=log, stderr=subprocess.STDOUT)
summary = dict(exit=result.returncode)
if mode != 'compile':
    cases = []
    for xml in reports.glob('*.xml'):
        tree = ET.parse(xml)
        for case in tree.findall('.//testcase'):
            failure = case.find('failure')
            if failure is None:
                failure = case.find('error')
            cases.append(dict(name=case.get('name'), classname=case.get('classname'),
                              passed=failure is None and case.find('skipped') is None,
                              skipped=case.find('skipped') is not None,
                              failure=None if failure is None else failure.get('message')))
        for parent in tree.iter():
            for child in list(parent):
                if child.tag in ('properties', 'system-out', 'system-err'):
                    parent.remove(child)
        tree.write(xml, encoding='utf-8', xml_declaration=True)
    summary.update(passed=sum(c['passed'] for c in cases), total=len(cases),
                   skipped=sum(c['skipped'] for c in cases), retries=[], cases=cases)
    summary['test_sources'] = {name: hashlib.sha256((product / 'libp2p/src/test/kotlin/io/libp2p/transport' / (name.split('#')[0] + '.kt')).read_bytes()).hexdigest() for name in classes}
(evidence / (label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({k: v for k, v in summary.items() if k not in ('cases', 'test_sources')}))
sys.exit(result.returncode)
