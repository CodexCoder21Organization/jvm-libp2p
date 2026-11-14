from pathlib import Path
import hashlib
import json
import subprocess
import sys

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
label, worktree, *sources = sys.argv[1:]
root = lane / worktree
output = lane / label
output.mkdir(exist_ok=True)
inputs = {s: hashlib.sha256((root / s).read_bytes()).hexdigest() for s in sources}
(evidence / (label + '-inputs.json')).write_text(json.dumps(inputs, indent=2))
classpath = str(lane / 'jctools.jar') + ':' + (lane / 'build-cp/runtime-classpath').read_text().strip()
compiler = (lane / 'build-cp/compiler-classpath').read_text().strip()
command = [str(evidence / 'with-slots.sh'), '1', 'timeout', '600',
           '/opt/java/bin/java', '-cp', compiler,
           'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
           '-jvm-target', '11', '-language-version', '1.6', '-Xjvm-default=all',
           '-Xfriend-paths=' + str(lane / 'head/libp2p/build/classes/kotlin/main'),
           '-classpath', classpath, '-d', str(output)]
command += [str(root / s) for s in sources]
with (evidence / (label + '-compile.log')).open('w') as log:
    result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
print('OBSERVED overlay compile exit=' + str(result.returncode))
sys.exit(result.returncode)
