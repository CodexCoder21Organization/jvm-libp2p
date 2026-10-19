from pathlib import Path
import subprocess,json,datetime,collections
b=Path(__file__).resolve().parent
rows=[]
def sec(s):return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def dur(s):return float(s[2:-1]) if s else 0
def family(n):
 if n.startswith('foundation/'):return 'consumer diagnostics/RPC'
 if n.startswith('spipe/') or '/noise/' in n:return 'Noise/protobuf'
 if '/yamux/' in n or '/mplex/' in n or '/mux/' in n:return 'mux'
 if n.startswith('io/netty/'):return 'Netty'
 if n.startswith('com/southernstorm/') or 'bouncycastle' in n or n.startswith('sun/security/') or n.startswith('java/security/'):return 'math/provider'
 if n.startswith('io/libp2p/'):return 'libp2p transport/selection'
 return 'JDK/other'
for prefix,root in [('phase',b.parent.parent/'flake-urlprotocol-dial9/evidence'),('fixed',b)]:
 for i in range(4):
  if not (root/f'{prefix}-{i}.jfr').exists(): continue
  path=b/f'{prefix}-{i}-classes.json'
  with path.open('w') as out:
   subprocess.run(['jfr','print','--json','--stack-depth','0','--events','jdk.ClassLoad,dial9.Interval',str(root/f'{prefix}-{i}.jfr')],stdout=out,check=True,timeout=60)
  events=json.loads(path.read_text())['recording']['events']
  for e in events:
   if e['type']!='dial9.Interval' or e['values']['phase']!='provider-to-connection':continue
   interval=e['values'];lo=sec(interval['startTime']);hi=lo+dur(interval['duration']);loads=[];groups=collections.defaultdict(list)
   for event in events:
    if event['type']!='jdk.ClassLoad':continue
    v=event['values'];a=max(lo,sec(v['startTime']));z=min(hi,sec(v['startTime'])+dur(v['duration']))
    if z<=a:continue
    name=v['loadedClass']['name'];f=family(name)
    loads.append(dict(name=name,family=f,ms=(z-a)*1000));groups[f].append((a,z))
   def union(pairs):
    total=0;end=-1
    for a,z in sorted(pairs):
     if z>end:total+=z-max(a,end);end=z
    return total*1000
   rows.append(dict(prefix=prefix,jvm=i,ordinal=interval['ordinal'],windowMs=(hi-lo)*1000,loads=sorted(loads,key=lambda x:-x['ms']),families={f:dict(count=len(pairs),unionMs=union(pairs),nestedSumMs=sum(z-a for a,z in pairs)*1000) for f,pairs in groups.items()}))
(b/'class-load-inventory.json').write_text(json.dumps(rows,indent=2))
for prefix in ['phase','fixed']:
 for ordinal in [0,1]:
  rs=[r for r in rows if r['prefix']==prefix and r['ordinal']==ordinal]
  if not rs: continue
  fs=sorted({f for r in rs for f in r['families']})
  print(prefix,ordinal,'n=',len(rs))
  for f in fs:
   print(f,dict(meanCount=sum(r['families'].get(f,{}).get('count',0) for r in rs)/len(rs),meanUnionMs=sum(r['families'].get(f,{}).get('unionMs',0) for r in rs)/len(rs)))
