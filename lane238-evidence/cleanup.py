from pathlib import Path
import os, subprocess, json, signal, time, shutil
lane=Path.cwd(); out=lane.parents[1]/'out'
owned_groups={392491,392531}
for path in Path('evidence').glob('*.log'):
 for line in path.read_text().splitlines():
  if line.startswith('RESULT '):
   result=json.loads(line[7:]); owned_groups.update(g for _,g in result.get('ownedProcesses',[]))
def active():
 rows=subprocess.check_output(['ps','-eo','pid=,pgid=,args=','-ww'],text=True).splitlines()
 return [row for row in rows if int(row.split(None,2)[1]) in owned_groups]
# Required raw process inspection stays local; only owned results are published.
(out/'r238-ps-before-cleanup.txt').write_text(subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True))
before=active()
for group in {int(row.split(None,2)[1]) for row in before}:
 if group==os.getpgrp(): raise RuntimeError('Refusing to terminate cleanup process group')
 try:os.killpg(group,signal.SIGTERM)
 except ProcessLookupError:pass
deadline=time.monotonic()+5
while active() and time.monotonic()<deadline:time.sleep(.1)
for group in {int(row.split(None,2)[1]) for row in active()}:
 try:os.killpg(group,signal.SIGKILL)
 except ProcessLookupError:pass
remaining=active()
assert not remaining,remaining
(out/'r238-ps-after-cleanup.txt').write_text(subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True))
deleted=[]
cache=Path.home()/'.aibuildcaches'
cache_deleted=[]
if cache.exists():
 for path in cache.iterdir():
  if 'lane238' in path.name and 'bc9e914b' in path.name:
   if path.is_dir() and not path.is_symlink():shutil.rmtree(path)
   else:path.unlink()
   cache_deleted.append(str(path))
for path in list(lane.iterdir()):
 if path.is_dir() and not path.is_symlink():shutil.rmtree(path)
 else:path.unlink()
 deleted.append(str(path))
result={'checkedWith':'ps -eo pid,args -ww','ownedProcessGroupsChecked':sorted(owned_groups),'activeOwnedBefore':before,'activeOwnedAfter':remaining,'deleted':deleted,'deletedCaches':cache_deleted,'remainingLaneEntries':[str(p) for p in lane.iterdir()],'rawProcessSnapshots':'local out/r238-ps-before-cleanup.txt and r238-ps-after-cleanup.txt only'}
(out/'r238-cleanup.json').write_text(json.dumps(result,indent=2)+'\n')
with (out/'r238-findings.md').open('a') as f:
 f.write('\n## '+time.strftime('%H:%M UTC',time.gmtime())+' — terminal cleanup confirmed\nOBSERVED: Required ps inspection confirms no active owned Gradle/Java process remains. Deleted every lane checkout, local evidence copy, scripts and outputs after remote checkpoints. Lane directory is empty; matching .aibuildcaches entries deleted: '+str(len(cache_deleted))+'. Shared Gradle/Coursier caches were left alone. Cleanup manifest: r238-cleanup.json. No monitor, background build, subagent, merge/queue, publication, deployment or Handoff action remains.\n')
print(json.dumps(result))
