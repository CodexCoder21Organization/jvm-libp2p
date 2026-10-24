from pathlib import Path
import re,json,xml.etree.ElementTree as ET,sys
folder=Path(sys.argv[1]);records=[]
for p in list(folder.glob('TEST-*.xml'))+list(folder.glob('concurrent-*.log')):
 if p.suffix=='.xml':
  root=ET.parse(p).getroot();text=root.findtext('system-out') or '';cases=[x.get('name') for x in root.findall('testcase')]
 else:text=p.read_text();cases=re.findall(r'CASE=(.*?) RESULT=',text)
 pattern=r'NOISE_PROBE_READY=(\d+)ms\nNOISE_PROBE_BODY_COMPLETE=(\d+)ms\nNOISE_PROBE_CLEANUP_COMPLETE=(\d+)ms\n(.*?)(?=NOISE_PROBE_READY=\d+ms|\Z)'
 for i,m in enumerate(re.finditer(pattern,text,re.S)):
  ready,body,cleanup=map(int,m.group(1,2,3));ops={n:int(t) for n,t in re.findall(r'NOISE_PROBE_OPERATION=(\w+) elapsed=(\d+)ms',m.group(4))}
  r={'file':p.name,'case':cases[i] if i<len(cases) else '?','ready':ready,'body':body,'cleanup':cleanup,'total_before_exit':ready+body+cleanup,'ops':ops}
  if 'JFR_START_DONE' in ops:
   r['jfr_body']=ops['JFR_START_DONE']-ops['STARTUP_DONE']+ops['JFR_WORK_DONE']-ops['STREAM_NEGOTIATED']+ops['EVENT_PARSING_DONE']-ops['CLOSURE_DONE']
   r['jfr_with_pre_ready_upper']=r['jfr_body']+ops['JFR_CONSTRUCTION_DONE']
  records.append(r)
(folder/'timings.json').write_text(json.dumps(records,indent=2))
print('PROBES',len(records))
for title,rs in [('WRAPPED_WS',[r for r in records if 'WebSocket host with delegating' in r['case']]),('ALL',records)]:
 if rs:print(title,'WORST',json.dumps(max(rs,key=lambda r:r['total_before_exit'])))
