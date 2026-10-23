from pathlib import Path
import subprocess,json,datetime
out=Path(__file__).parent
cp=(out/'FIX67-develop-classpath.txt').read_text()
rows=[]
for i in range(1,11):
 label=f'FIX67-develop-noise-harness-{i}'
 before=int(Path('/sys/fs/cgroup/memory.current').read_text())
 with (out/(label+'.log')).open('w') as log:
  try: r=subprocess.run(['timeout','--kill-after=5s','30s','java','-cp',cp,'io.libp2p.security.noise.NoiseHandshakePathHarness','saturated'],stdout=log,stderr=subprocess.STDOUT,timeout=40); code=r.returncode
  except subprocess.TimeoutExpired: code=124
 row={'iteration':i,'exit':code,'memory_before':before,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 rows.append(row); (out/'FIX67-develop-noise-harness-summary.json').write_text(json.dumps(rows,indent=2)+'\n')
 print(row,flush=True)
print('baseline failures',sum(r['exit']!=0 for r in rows),'of',len(rows),flush=True)
