import json,datetime,re,collections,pathlib,subprocess
b=pathlib.Path(__file__).resolve().parent
def sec(s):return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def dur(s):return float(s[2:-1]) if s else 0
def frames(v):return [f['method']['type']['name']+'.'+f['method']['name'] for f in (v.get('stackTrace') or {}).get('frames',[])]
def union(pairs):
 a=0;end=-1
 for x,y in sorted(pairs):
  if y>end:a+=y-max(x,end);end=y
 return a*1000
def fam(names):
 st='\n'.join(names)
 if any(x in st for x in ['ClassLoader','URLClassPath','defineClass','Class.forName','initializeFromArchive']):return 'class-load'
 if any(x in st for x in ['Curve25519','Ed25519','EdDSA','BigInteger','BouncyCastle','bouncycastle','ChaChaPoly','SHA256Digest','MessageDigest','NoiseIoHandshake.initializeHandshake']):return 'handshake-math'
 if any(x in st for x in ['noise/protocol','security/noise']):return 'noise-other'
 if any(x in st for x in ['.park','EPoll.wait','SelectorImpl.select','NioIoHandler.select']):return 'wait/native-poll'
 if any(x in st for x in ['ChannelPipeline','ChannelInitializer','AbstractBootstrap','multistream','Yamux','yamux','Mplex','mplex']):return 'pipeline/mux-init'
 return 'other'
all=[]
for i in range(4):
 p=b/f'phase-{i}.json'
 if not p.exists():
  with p.open('w') as o:subprocess.run(['jfr','print','--json','--stack-depth','80','--events','dial9.Interval,jdk.ClassLoad,jdk.Compilation,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.GarbageCollection',str(b/f'phase-{i}.jfr')],stdout=o,check=True)
 events=json.load(p.open())['recording']['events']
 for interval in [e['values'] for e in events if e['type']=='dial9.Interval' and e['values']['phase']=='provider-to-connection']:
  lo=sec(interval['startTime']);hi=lo+dur(interval['duration'])
  sums=collections.Counter();counts=collections.Counter();pairs=collections.defaultdict(list);sample=collections.Counter();stacks=collections.Counter();loads=collections.Counter();comps=collections.Counter();parks=[];sampleThread=collections.Counter()
  for e in events:
   v=e['values'];s=sec(v['startTime']);d=dur(v.get('duration'));t=e['type'];n=frames(v)
   if t in ['jdk.ExecutionSample','jdk.NativeMethodSample']:
    if lo<=s<hi:
     f=fam(n);sample[f]+=1;stacks[tuple(n[:8])]+=1;sampleThread[(v['sampledThread']['javaName'],f)]+=1
   else:
    overlap=max(0,min(hi,s+d)-max(lo,s))
    if not overlap:continue
    sums[t]+=overlap*1000;counts[t]+=1;pairs[t].append((max(lo,s),min(hi,s+d)))
    if t=='jdk.ClassLoad':loads[v['loadedClass']['name']]+=overlap*1000
    if t=='jdk.Compilation':comps[v['method']['type']['name']+'.'+v['method']['name']]+=overlap*1000
    if t in ['jdk.ThreadPark','jdk.JavaMonitorEnter']:parks.append(dict(thread=v['eventThread']['javaName'],ms=overlap*1000,stack=n[:12],parkedClass=v.get('parkedClass')))
  row=dict(jvm=i,ordinal=interval['ordinal'],window_ms=(hi-lo)*1000,event_ms=dict(sums),counts=dict(counts),union_ms={t:union(p) for t,p in pairs.items()},sample_counts=dict(sample),sample_thread=[dict(thread=t,family=f,count=c) for (t,f),c in sampleThread.most_common()],top_loads=loads.most_common(20),top_compilations=comps.most_common(20),top_stacks=[dict(count=c,frames=list(s)) for s,c in stacks.most_common(20)],parks=parks)
  all.append(row)
(b/'phase-summary.json').write_text(json.dumps(all,indent=2))
for r in all:
 print(json.dumps({k:r[k] for k in ['jvm','ordinal','window_ms','counts','event_ms','union_ms','sample_counts']}))
 print('TOP CLASSES',r['top_loads'][:8]);print('TOP JIT',r['top_compilations'][:7]);print('TOP STACKS',r['top_stacks'][:3])
