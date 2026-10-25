from pathlib import Path
import sys,json,re,datetime,collections,subprocess
root=Path(sys.argv[1]);rows=[];outputs=[]
def stamp(s):return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def duration(s):return float(s[2:-1])
for recording in sorted(root.rglob('*.jfr')):
 f=recording.with_suffix('.events.json')
 if not f.exists():
  with f.open('w') as out: subprocess.run(['jfr','print','--json',str(recording)],stdout=out,check=True)
 events=json.loads(f.read_text())['recording']['events'];assert all(e['values'].get('stackTrace') is None for e in events)
 lines=recording.with_suffix('').read_text().splitlines()
 def marker(name):return int(re.search(r'epoch=(\d+)',next(x for x in lines if name in x))[1])/1000
 a,b=marker('application.request.start'),marker('application.reply.arrived');cpu=collections.Counter();prior={};loads=[];monitor=[]
 cpuTimes=sorted(set(stamp(e['values']['startTime']) for e in events if e['type']=='jdk.ThreadCPULoad'))
 previousSample={t:(cpuTimes[i-1] if i else t-.02) for i,t in enumerate(cpuTimes)}
 for e in sorted(events,key=lambda e:e['values']['startTime']):
  v=e['values'];t=stamp(v['startTime']);name=(v.get('eventThread')or{}).get('javaName','?')
  if e['type']=='jdk.ThreadCPULoad':
   tid=v['eventThread']['javaThreadId'];previous=previousSample[t]
   overlap=max(0,min(t,b)-max(previous,a));cpu[name]+=overlap*(v['user']+v['system'])*1000*2
  if e['type']=='jdk.ClassLoad' and a<=t<=b:loads.append((duration(v['duration'])*1000,name,(v.get('loadedClass')or{}).get('name','?')))
  if e['type']=='jdk.JavaMonitorEnter' and a<=t<=b:monitor.append((duration(v['duration'])*1000,name,v['monitorClass']['name']))
 rows.append('\n'+recording.name+' callMs='+str(round((b-a)*1000)))
 rows.append('CPU estimates from shared sampling intervals, scaled to two active processors (ms): '+str([(k,round(v,1)) for k,v in cpu.most_common(25)]))
 groups=collections.Counter()
 for k,v in cpu.items():
  group='compiler' if 'CompilerThread' in k else 'netty' if 'libp2p-nio' in k else 'one-shot-gossip' if 'url-one-shot' in k else 'resolver-coroutines' if 'url-resolver-coroutine' in k else 'other'
  groups[group]+=v
 rows.append('CPU groups: '+str({k:round(v,1) for k,v in groups.items()}))
 rows.append('longest class loads ms, thread, class: '+str(sorted(loads,reverse=True)[:20]));rows.append('longest monitors: '+str(sorted(monitor,reverse=True)[:10]))
 outputs.append(dict(name=recording.name,callMs=(b-a)*1000,cpuMs=dict(cpu),cpuGroupMs=dict(groups),classLoads=sorted(loads,reverse=True),monitors=sorted(monitor,reverse=True)))
(root/'cpu-analysis.json').write_text(json.dumps(outputs,indent=2));(root/'cpu-analysis.txt').write_text('\n'.join(rows));print('\n'.join(rows))
