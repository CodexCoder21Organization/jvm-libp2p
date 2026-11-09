from pathlib import Path
import json
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
tag, *selectors = sys.argv[1:]
if not selectors:
    raise SystemExit('Provide at least one targeted native selector')
destination = root / '.lane-evidence' / tag
destination.mkdir(parents=True, exist_ok=True)
subprocess.run(['git', 'fetch', 'origin'], cwd=root, check=True)
subprocess.run(['git', '-c', 'rebase.autoStash=true', 'rebase', 'origin/develop'], cwd=root, check=True)
reports = root / 'libp2p/build/test-results/test'
for report in reports.glob('TEST-*.xml'):
    report.unlink()
command = ['/tmp/claude-1000/-code/84b03628-0ec5-4336-aaa6-4c48b6b32072/scratchpad/tools/boxrun',
           'timeout', '900', './gradlew', '--no-daemon', '--max-workers=2', ':libp2p:test', '--rerun']
for selector in selectors:
    command.extend(['--tests', selector])
(destination / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
start = time.monotonic()
with (destination / 'run.log').open('w') as log:
    process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
    (destination / 'pid.txt').write_text(str(process.pid) + '\n')
    rc = process.wait(timeout=3660)
total = failed = skipped = 0
for report in reports.glob('TEST-*.xml'):
    shutil.copy2(report, destination / report.name)
    suite = ET.parse(report).getroot()
    total += int(suite.get('tests', 0))
    failed += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
    skipped += int(suite.get('skipped', 0))
summary = f'{tag}: FIRST {total - failed - skipped}/{total}; failed={failed}; skipped={skipped}; exit={rc}; elapsed={time.monotonic()-start:.1f}s'
(destination / 'summary.txt').write_text(summary + '\n')
print(summary, flush=True)
raise SystemExit(rc if rc else (1 if not total or failed or skipped else 0))
