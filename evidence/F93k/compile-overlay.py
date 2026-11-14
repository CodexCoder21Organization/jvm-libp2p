from pathlib import Path
import subprocess
import sys

lane = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
label, worktree, *sources = sys.argv[1:]
output = lane / label
output.mkdir(exist_ok=True)
classpath = (lane / 'build-cp/runtime-classpath').read_text().strip()
compiler = (lane / 'build-cp/compiler-classpath').read_text().strip()
command = [str(evidence / 'with-slots.sh'), '1', 'timeout', '600',
           '/opt/java/bin/java', '-cp', compiler,
           'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
           '-jvm-target', '11', '-language-version', '1.6', '-Xjvm-default=all',
           '-Xfriend-paths=' + str(lane / 'jvm-libp2p/libp2p/build/classes/kotlin/main'),
           '-classpath', classpath, '-d', str(output)]
command += [str(lane / worktree / source) for source in sources]
with (evidence / (label + '-compile.log')).open('w') as log:
    result = subprocess.run(command, cwd=lane / worktree, stdout=log, stderr=subprocess.STDOUT)
print('OBSERVED overlay compile exit=' + str(result.returncode))
sys.exit(result.returncode)
