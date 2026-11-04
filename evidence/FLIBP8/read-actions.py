from pathlib import Path
from datetime import datetime, timezone
import json, subprocess, sys, time
repo, run_id, output = sys.argv[1:]
out = Path(output)
marker = out.parent / 'last-actions-read.time'
now = time.time()
last = float(marker.read_text()) if marker.exists() else 0
if now - last < 300:
    print(f'WAIT: next Actions read in {300 - (now - last):.0f} seconds; no GitHub request made')
    sys.exit(75)
marker.write_text(str(now))
with out.open('w') as stdout, out.with_suffix('.stderr').open('w') as stderr:
    result = subprocess.run(['gh', 'run', 'view', run_id, '--repo', repo, '--json', 'jobs,headSha,status,conclusion,url'], stdout=stdout, stderr=stderr)
if result.returncode:
    print(f'PROBLEM: Actions read exited {result.returncode}; see {out.with_suffix(".stderr")}')
    sys.exit(result.returncode)
data = json.loads(out.read_text())
print(json.dumps({key:data[key] for key in ['headSha','status','conclusion','url']}))
for job in data['jobs']:
    print(json.dumps({'job':job['name'],'status':job['status'],'conclusion':job['conclusion'],'latest_steps':[(step['name'],step['status'],step['conclusion']) for step in job['steps'] if step['status'] != 'pending'][-3:]}))
