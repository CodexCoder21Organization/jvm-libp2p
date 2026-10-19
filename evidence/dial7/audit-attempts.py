import sys, pathlib, json, urllib.request, gzip, collections, datetime, time
run, output = sys.argv[1:3]
page_limit=int(sys.argv[3]) if len(sys.argv)>3 else 500
p=pathlib.Path(output);p.mkdir(parents=True,exist_ok=True)
allrows=[]
page=1
deadline=time.monotonic()+240
while True:
 if time.monotonic()>=deadline or page>100:raise RuntimeError("Attempt audit exceeded its 240-second/100-page limit")
 url=f'https://buildtest.kotlin.build/api/test-results?id={run}&limit={page_limit}&page={page}'
 with urllib.request.urlopen(url,timeout=max(1,min(45,deadline-time.monotonic()))) as r: raw=r.read()
 data=json.loads(raw)
 (p/f'page{page}.json.gz').write_bytes(gzip.compress(raw))
 allrows.extend(data['results'])
 if page>=data['totalPages']:break
 page+=1
rows=[r for r in allrows if r.get('status')!='FILTERED_OUT']
(p/'selected.json').write_text(json.dumps(rows,indent=2))
summary={'run':run,'pages':page,'rows':len(allrows),'selected':len(rows),'selectedStatus':dict(collections.Counter(r.get('status') for r in rows)),'firstAttempts':dict(collections.Counter(a.get('status') for r in rows for a in r.get('attempts',[]) if a.get('attemptNumber')==1)),'missingHistories':[r['testName'] for r in rows if not r.get('attempts')], 'multiAttempts':{r['testName']:[(a.get('attemptNumber'),a.get('status')) for a in r.get('attempts',[])] for r in rows if len(r.get('attempts',[]))>1},'lastPageMetadata':{k:v for k,v in data.items() if k!='results'}}
(p/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps({**summary,'missingHistories':summary['missingHistories'] if len(summary['missingHistories'])<=20 else {'count':len(summary['missingHistories'])}},indent=2))
for r in rows:
 if any(a.get('status')!='PASSED' for a in r.get('attempts',[])) or r.get('status')=='FAILED':
  print(r['testName'],[(a.get('attemptNumber'),a.get('status')) for a in r.get('attempts',[])],r.get('errorMessage','')[:1500])
