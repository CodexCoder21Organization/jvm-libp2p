from pathlib import Path
import json,re,datetime,collections,subprocess,sys
root=Path(sys.argv[1]);total=collections.defaultdict(collections.Counter);counts=collections.Counter();summaries=[]
def ts(s):return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def method(m):return m['type']['name']+'.'+m['name']
for f in sorted(root.rglob('*.jfr')):
 out=f.with_suffix('.samples.json')
 with out.open('w') as dst:subprocess.run(['jfr','print','--json','--events','jdk.ExecutionSample',str(f)],stdout=dst,check=True)
 events=json.loads(out.read_text())['recording']['events'];lines=f.with_suffix('').read_text().splitlines()
 def marker(s):return int(re.search(r'epoch=(\d+)',next(l for l in lines if s in l))[1])/1000
 a,b=marker('application.request.start'),marker('application.reply.arrived');own=collections.Counter();leaf=collections.Counter();phasecount=collections.Counter()
 for event in events:
  v=event['values'];phase='request' if a<=ts(v['startTime'])<=b else 'before' if ts(v['startTime'])<a else 'after';phasecount[phase]+=1
  name=(v.get('sampledThread')or v.get('eventThread')or{}).get('javaName','unknown');group='netty' if 'libp2p-nio' in name else 'other';frames=(v.get('stackTrace')or{}).get('frames',[])
  if not frames:continue
  methods=[method(fr['method']) for fr in frames];key=phase+'/'+group;counts[key]+=1;total[key+'/leaf'][methods[0]]+=1
  project=next((m for m in methods if m.startswith(('foundation/','foundation.','io/libp2p/','io.libp2p.'))),None)
  if project:total[key+'/nearest-project'][project]+=1
  for m in set(methods):total[key+'/inclusive'][m]+=1
  if phase=='request':
   leaf[methods[0]]+=1
   if project:own[project]+=1
 summaries.append(dict(name=f.name,phaseSamples=dict(phasecount),requestLeaf=leaf.most_common(25),requestNearestProject=own.most_common(25)))
(root/'method-summary.json').write_text(json.dumps(dict(counts=dict(counts),perScenario=summaries,aggregate={k:v.most_common(40) for k,v in total.items()}),indent=2))
print('Sample counts:',dict(counts))
for key in sorted(total):
 if key.startswith('request/'):
  print('\n'+key);print('\n'.join(str(n)+' '+m for m,n in total[key].most_common(20)))
