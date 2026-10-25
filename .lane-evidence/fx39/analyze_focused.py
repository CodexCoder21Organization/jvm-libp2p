from pathlib import Path
import sys,json
result={}
for p in Path(sys.argv[1]).rglob('testFx39Diagnostic*.txt'):
 rows=[]
 for line in p.read_text().splitlines():
  left,extra=line.split(' | ',1);t,thread,event=left.split(' ',2)
  vals=dict(v.split('=',1) for v in extra.split())
  rows.append({'ns':int(t),'thread':thread,'event':event,**{k:int(v) for k,v in vals.items()}})
 rows.sort(key=lambda r:r['ns'])
 start=next((r for r in rows if r['event']=='application.request.start'),None)
 end=next((r for r in rows if r['event']=='application.reply.arrived'),None)
 if start is None or end is None: print(p.name,'missing complete request');continue
 selected=[r for r in rows if start['ns']<=r['ns']<=end['ns']]
 result[p.stem]=selected
 print('\n'+p.stem+' reply_ms='+str(round((end['ns']-start['ns'])/1e6,3)))
 previous={}
 for r in selected:
  prior=previous.get(r['tid']);previous[r['tid']]=r
  wall=(r['ns']-prior['ns'])/1e6 if prior else 0
  cpu=(r['cpu']-prior['cpu'])/1e6 if prior and min(r['cpu'],prior['cpu'])>=0 else 0
  print(f"{(r['ns']-start['ns'])/1e6:8.2f} cpu={r['cpu']/1e6:8.2f} lastThreadWall={wall:7.2f} lastThreadCpu={cpu:7.2f} newClasses={r['classes']-prior['classes'] if prior else 0:4} {r['thread']} {r['event']}")
Path(sys.argv[1],'parsed-focused.json').write_text(json.dumps(result,indent=2))
