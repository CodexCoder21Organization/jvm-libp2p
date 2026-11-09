from pathlib import Path
import json
import subprocess
import sys

e = Path(__file__).resolve().parent
lane = e.parents[2]
name, checkout, *classes = sys.argv[1:]
repo = lane / checkout
args = [str(e / 'with-slots.sh'), '2', 'timeout', '--kill-after=15s', '1200', './gradlew', '--no-daemon', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process', '-I', str(e / 'control.gradle'), '-Dr93t.xml=' + str(e / (name + '-xml')), ':libp2p:test']
if classes == ['changed']:
    files = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop...12cb229e'], cwd=repo, text=True).splitlines()
    classes = ['io.libp2p.transport.' + Path(p).stem for p in files if '/src/test/' in p and p.endswith('.kt')]
for cls in classes:
    args += ['--tests', cls]
(e / (name + '-request.json')).write_text(json.dumps({'source_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(), 'classes': classes, 'attempts_per_case': 1, 'slots': 2}, indent=2))
with (e / (name + '-test.log')).open('w') as log:
    p = subprocess.Popen(args, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    pidfile = e / (name + '-driver.pid')
    pidfile.write_text(str(p.pid))
    code = p.wait()
    pidfile.unlink()
(e / (name + '-test.exit')).write_text(str(code))
print('OBSERVED:', name, 'run exited', code, '; requested', len(classes), 'classes.')
