from pathlib import Path
import subprocess
import hashlib
import json

evidence = Path(__file__).resolve().parent
root = evidence.parents[2]
review = root / 'jvm-libp2p-review'
build = root / 'r93a-build'
junit = '/tmp/F93i-junit-console.jar'
sources = sorted(evidence.glob('R93A*Test.kt'))
(build / 'probes').mkdir(exist_ok=True)
(build / 'gate').mkdir(exist_ok=True)
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=review, text=True).strip()
assert head == '12cb229eb8f6baf50c060ac9bc29156611301af8', head
commands = [
    ['/opt/java/bin/javac', '-cp', junit, '-d', str(build / 'gate'), str(evidence / 'F93dGate.java')],
    ['/opt/java/bin/java', '-cp', (build / 'compiler-classpath').read_text(),
     'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '11',
     '-language-version', '1.6', '-Xjvm-default=all', '-classpath',
     junit + ':' + (build / 'runtime-classpath').read_text(), '-d', str(build / 'probes')]
    + [str(p) for p in sources]
]
(evidence / 'probe-inputs.json').write_text(json.dumps(dict(head=head, sources=[
    dict(path=p.name, sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources]), indent=2))
with (evidence / 'compile-probes.log').open('w') as log:
    for command in commands:
        subprocess.run([str(evidence / 'with-slots.sh'), '1', 'timeout', '600'] + command,
                       cwd=review, stdout=log, stderr=subprocess.STDOUT, check=True)
print('OBSERVED: compiled probes=' + str(len(sources)) + ', exact head=' + head)
