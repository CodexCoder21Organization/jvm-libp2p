import datetime,json,pathlib,time
root=344535
rootPath=pathlib.Path('/proc')/str(root)
def readStat(path):
 s=(path/'stat').read_text();return s[s.rfind(')')+2:].split()
identity=readStat(rootPath)[19]
deadline=time.monotonic()+2200
with pathlib.Path('/home/u/lanes/hd27/out/hq054-full-processes.jsonl').open('w') as output:
 while time.monotonic()<deadline:
  try:
   current=readStat(rootPath)
   if current[19]!=identity or current[0] in ['Z','X']:break
  except OSError:break
  allp={}
  for path in pathlib.Path('/proc').iterdir():
   if not path.name.isdigit():continue
   try:
    s=readStat(path);allp[int(path.name)]=(int(s[1]),path,s)
   except OSError:pass
  keep={root}
  while True:
   new=keep|{pid for pid,(parent,_,_) in allp.items() if parent in keep}
   if new==keep:break
   keep=new
  rows=[]
  for pid in keep:
   if pid not in allp:continue
   _,path,s=allp[pid]
   try:
    cmd=(path/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
    if not (cmd.startswith('java ') or '/bin/java ' in cmd):continue
    status=dict(line.split(':',1) for line in (path/'status').read_text().splitlines() if ':' in line)
    rows.append({'pid':pid,'state':s[0],'startTicks':s[19],'minorFaults':int(s[7]),'majorFaults':int(s[9]),'cpuUserTicks':int(s[11]),'cpuSystemTicks':int(s[12]),'mainScheduler':(path/'schedstat').read_text().strip(),'rss':status.get('VmRSS','').strip(),'swap':status.get('VmSwap','').strip(),'command':cmd[:550]})
   except OSError:pass
  output.write(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'processes':rows})+'\n');output.flush()
  time.sleep(2)
print('FULL_OBSERVER_FINISHED',flush=True)
