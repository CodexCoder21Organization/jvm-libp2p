import pathlib
exec((pathlib.Path(__file__).parent/'analyze-jfr.py').read_text().split('all=[]')[0])
rows=[]
for i in range(4):
 p=b/f'minimal-{i}.json'
 with p.open('w') as o:subprocess.run(['jfr','print','--json','--stack-depth','1','--events','dial9.Work,dial9.Interval,jdk.ClassLoad,jdk.Compilation,jdk.ThreadPark,jdk.JavaMonitorEnter',str(b/f'minimal-{i}.jfr')],stdout=o,check=True)
 events=json.load(p.open())['recording']['events']
 for interval in [e['values'] for e in events if e['type']=='dial9.Interval' and e['values']['phase']=='public-dial']:
  lo=sec(interval['startTime']);hi=lo+dur(interval['duration'])
  conn=[e['values'] for e in events if e['type']=='dial9.Interval' and e['values']['ordinal']==interval['ordinal'] and e['values']['phase']=='provider-to-connection']
  if conn:lo=sec(conn[0]['startTime']);hi=lo+dur(conn[0]['duration'])
  groups=collections.defaultdict(list);method=collections.defaultdict(list);cpu=collections.Counter();counts=collections.Counter();jit=collections.Counter();parks=[];initial=[]
  for e in events:
   t=e['type'];v=e['values'];s=sec(v['startTime']);d=dur(v.get('duration'));a=max(lo,s);z=min(hi,s+d)
   if z<=a:continue
   if t=='dial9.Work':
    f=v['family'];groups[f].append((a,z));method[v['method']].append((a,z));counts[v['method']]+=1
    cpu[v['method']]+=v['cpuNanos']/1e6*(z-a)/d
    if f=='class-init':initial.append(dict(method=v['method'],ms=(z-a)*1000,cpu=v['cpuNanos']/1e6))
   if t=='jdk.ClassLoad':groups['class-load'].append((a,z))
   if t=='jdk.Compilation':groups['jit'].append((a,z));jit[v['method']['type']['name']+'.'+v['method']['name']]+=(z-a)*1000
   if t in ['jdk.ThreadPark','jdk.JavaMonitorEnter']:parks.append(dict(type=t,thread=v['eventThread']['javaName'],ms=(z-a)*1000,stack=frames(v)))
  # disjoint wall accounting with explicit precedence; compilation is concurrent diagnostic, not added to wall partition
  edges=sorted(set([lo,hi]+[s for f,p in groups.items() if f!='jit' and f!='noise' for ab in p for s in ab]))
  partition=collections.Counter()
  for a,z in zip(edges,edges[1:]):
   m=(a+z)/2
   f=next((f for f in ['class-load','class-init','math','pipeline'] if any(x<=m<y for x,y in groups[f])),'other')
   partition[f]+=(z-a)*1000
  row=dict(jvm=i,ordinal=interval['ordinal'],connected=bool(conn),window_ms=(hi-lo)*1000,union_ms={f:union(p) for f,p in groups.items()},partition_ms=dict(partition),method_union_ms={m:union(p) for m,p in method.items()},method_cpu_ms=dict(cpu),method_counts=dict(counts),jit=jit.most_common(12),class_init=initial,parks=parks)
  rows.append(row)
(b/'minimal-summary.json').write_text(json.dumps(rows,indent=2))
for r in rows:
 print('WINDOW',r['jvm'],r['ordinal'],r['connected'],round(r['window_ms'],2),'PARTITION',r['partition_ms'],'UNION',r['union_ms'])
 print('METHODS',sorted(r['method_union_ms'].items(),key=lambda x:-x[1])[:12]);print('CPU',sorted(r['method_cpu_ms'].items(),key=lambda x:-x[1])[:10]);print('INIT',r['class_init']);print('JIT',r['jit'][:6])
