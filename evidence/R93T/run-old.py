from pathlib import Path
import json
import subprocess

e = Path(__file__).resolve().parent
root = e.parents[2]
old = root / 'old-control'
repo = root / 'jvm-libp2p'
files = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop...12cb229e'], cwd=repo, text=True).splitlines()
tests = [p for p in files if '/src/test/' in p and p.endswith('.kt')]
args = [str(e / 'with-slots.sh'), '2', 'timeout', '--kill-after=15s', '1200', './gradlew', '--no-daemon', '--max-workers=1', '-Pkotlin.compiler.execution.strategy=in-process', '-I', str(e / 'control.gradle'), '-Dr93t.xml=' + str(e / 'old-xml'), ':libp2p:test']
for file in tests:
    args += ['--tests', 'io.libp2p.transport.' + Path(file).stem]
(e / 'old-request.json').write_text(json.dumps({'source_head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=old, text=True).strip(), 'tests_head': '12cb229eb8f6baf50c060ac9bc29156611301af8', 'test_files': tests, 'attempts_per_case': 1, 'slots': 2}, indent=2))
with (e / 'old-test.log').open('w') as log:
    p = subprocess.Popen(args, cwd=old, stdout=log, stderr=subprocess.STDOUT)
    (e / 'old-driver.pid').write_text(str(p.pid))
    code = p.wait()
(e / 'old-driver.pid').unlink()
(e / 'old-test.exit').write_text(str(code))
print('OBSERVED: Old-source targeted run exited', code, '; requested', len(tests), 'classes.')
