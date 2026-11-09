from pathlib import Path
import subprocess, json, hashlib, sys

e = Path(__file__).resolve().parent
lane = e.parents[2]
repo = lane / 'jvm-libp2p'
build = lane / 'f93h-build'
subprocess.run(['git', 'fetch', 'origin'], cwd=repo, check=True, stdout=subprocess.DEVNULL)
subprocess.run(['git', 'rebase', 'origin/develop'], cwd=repo, check=True, stdout=subprocess.DEVNULL)
arguments = sys.argv[1:]
output_name = next((x.split('=', 1)[1] for x in arguments if x.startswith('--output=')), 'overlay')
files = [Path(x).resolve() for x in arguments if not x.startswith('--output=')]
manifest = dict(head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(), inputs=[dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in files])
(e / (output_name + '-compile-inputs.json')).write_text(json.dumps(manifest, indent=2))
(build / output_name).mkdir(parents=True, exist_ok=True)
(build / 'gate').mkdir(parents=True, exist_ok=True)
commands = [
    ['/opt/java/bin/javac', '-cp', '/tmp/F93h-junit-console.jar', '-d', str(build / 'gate'), str(e / 'F93dGate.java')],
    ['/opt/java/bin/java', '-cp', (build / 'compiler-classpath').read_text(), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-language-version', '1.6', '-Xjvm-default=all', '-Xfriend-paths=' + str(repo / 'libp2p/build/classes/kotlin/main'), '-classpath', '/tmp/F93h-junit-console.jar:' + (build / 'runtime-classpath').read_text(), '-d', str(build / output_name)] + [str(p) for p in files],
]
if (build / 'gate/F93dGate.class').exists():
    commands.pop(0)
with (e / (output_name + '-compile.log')).open('w') as output:
    for command in commands:
        result = subprocess.run([str(e / 'with-slots.sh'), '1', 'timeout', '600'] + command, cwd=repo, stdout=output, stderr=subprocess.STDOUT)
        if result.returncode:
            print('Evidence compile exit', result.returncode)
            sys.exit(result.returncode)
print('Evidence launcher and', len(files), 'source inputs compiled')
