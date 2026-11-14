from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
workspace = evidence.parents[2]
label, reference, test, *extra = sys.argv[1:]
repo = workspace / 'head'
source_path = extra[0] if extra else 'libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt'
control_root = workspace / 'controls' / label
source = control_root / source_path
source.parent.mkdir(parents=True, exist_ok=True)
source.write_bytes(subprocess.check_output(['git', 'show', reference + ':' + source_path], cwd=repo))
if len(extra) > 1:
    patch = (evidence / extra[1]).resolve()
    with (evidence / (label + '-patch.log')).open('w') as log:
        subprocess.run(['git', 'apply', '--recount', '--whitespace=nowarn', str(patch)], cwd=control_root, stdout=log, stderr=subprocess.STDOUT, check=True)
classes = source.parent / 'classes'
classes.mkdir(exist_ok=True)
runtime = (workspace / 'build-cp/runtime-classpath').read_text().strip()
compiler = (workspace / 'build-cp/compiler-classpath').read_text().strip()
inputs = {'reference': reference, 'path': source_path, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'test': test}
(evidence / (label + '-inputs.json')).write_text(json.dumps(inputs, indent=2))
compile_command = [str(evidence / 'with-slots.sh'), '1', 'timeout', '300', '/opt/java/bin/java', '-cp', compiler,
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '11',
                   '-language-version', '1.6', '-Xjvm-default=all', '-Xfriend-paths=' + str(repo / 'libp2p/build/classes/kotlin/main'),
                   '-classpath', runtime, '-d', str(classes), str(source)]
with (evidence / (label + '-compile.log')).open('w') as log:
    code = subprocess.run(compile_command, cwd=repo, stdout=log, stderr=subprocess.STDOUT).returncode
if code:
    print(json.dumps({'label': label, 'compile_exit': code}))
    raise SystemExit(code)
output = evidence / (label + '-xml')
output.mkdir(exist_ok=True)
command = [str(evidence / 'with-slots.sh'), '2', 'timeout', '180', '/opt/java/bin/java', '-jar', str(workspace / 'junit-console.jar'),
           'execute', '--disable-banner', '--details=summary', '--class-path', str(classes) + ':' + runtime,
           '--reports-dir', str(output), '--select-class', 'io.libp2p.transport.' + test]
with (evidence / (label + '-test.log')).open('w') as log:
    code = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT).returncode
cases = []
for path in output.glob('*.xml'):
    tree = ET.parse(path)
    for case in tree.findall('.//testcase'):
        failures = case.findall('failure') + case.findall('error')
        cases.append({'name': case.get('name'), 'class': case.get('classname'), 'passed': not failures and case.find('skipped') is None,
                      'failures': [{'message': f.get('message'), 'trace': f.text} for f in failures]})
    for node in tree.iter():
        for child in list(node):
            if child.tag in ('properties', 'system-out', 'system-err'):
                node.remove(child)
    tree.write(path, encoding='utf-8', xml_declaration=True)
summary = {'label': label, 'compile_exit': 0, 'exit': code, 'passed': sum(c['passed'] for c in cases), 'total': len(cases), 'attempts': len(cases), 'cases': cases}
(evidence / (label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps({k:v for k,v in summary.items() if k != 'cases'}))
raise SystemExit(code)
