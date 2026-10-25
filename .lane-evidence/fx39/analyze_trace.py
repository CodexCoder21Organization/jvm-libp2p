from pathlib import Path
import sys,json
all_rows={}
for p in Path(sys.argv[1]).rglob('testFx39Diagnostic*.txt'):
 events=[]
 for line in p.read_text().splitlines():
  t,thread,event=line.split(' ',2);events.append((int(t),thread,event))
 events.sort()
 start=next((t for t,th,e in events if e=='application.request.start'),None)
 if start is None: print(p,'NO REQUEST START');continue
 end=next((t for t,th,e in events if e=='application.reply.arrived'),events[-1][0])
 print('\n',p,'reply_ms',round((end-start)/1e6,2),'events',len(events))
 rows=[]
 for t,th,e in events:
  if start<=t<=end:
   row={'ms':round((t-start)/1e6,3),'thread':th,'event':e};rows.append(row)
   print(f'{row["ms"]:9.3f} {th} {e}')
 all_rows[p.stem]={'reply_ms':(end-start)/1e6,'events':rows}
Path(sys.argv[1],'parsed-trace.json').write_text(json.dumps(all_rows,indent=2))
