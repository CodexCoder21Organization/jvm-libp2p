import os, signal, subprocess, json, time, shutil, sys
from pathlib import Path
lane=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/workspace/lane229')
out=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out')
if sys.argv[1]=='processes':
    before=subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True)
    (out/'r229-ps-before-cleanup.txt').write_text(before)
    groups={269620,269679}
    for p in out.glob('r229-*-groups.json'): groups.update(json.loads(p.read_text()))
    fixture=lane/'kubo-fixture/daemon-pid.json'
    if fixture.exists(): groups.add(json.loads(fixture.read_text())['pid'])
    def owned():
        rows=[r.split(None,3) for r in subprocess.check_output(['ps','-eo','pid,pgid,comm,args','-ww'],text=True).splitlines()[1:]]
        return [(int(r[0]),int(r[1]),r[2]) for r in rows if int(r[1]) in groups and r[2] in ['java','ipfs']]
    initial=owned()
    for _,group,_ in initial:
        try: os.killpg(group,signal.SIGTERM)
        except ProcessLookupError: pass
    deadline=time.monotonic()+10
    while owned() and time.monotonic()<deadline: time.sleep(.2)
    for _,group,_ in owned():
        try:os.killpg(group,signal.SIGKILL)
        except ProcessLookupError:pass
    after=subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True)
    (out/'r229-ps-after-cleanup.txt').write_text(after)
    remaining=[]
    for pid,group,comm in owned():
        try:state=Path(f'/proc/{pid}/stat').read_text().split()[2]
        except FileNotFoundError:continue
        if state!='Z':remaining.append({'pid':pid,'group':group,'comm':comm})
    manifest={'terminated':initial,'remainingActive':remaining,'checkedWith':'ps -eo pid,args -ww','rawProcessSnapshots':'local only'}
    (out/'r229-process-cleanup.json').write_text(json.dumps(manifest,indent=2)); print(json.dumps(manifest)); assert not remaining
elif sys.argv[1]=='files':
    deleted=[]
    for name in ['jvm-libp2p','evidence','kubo-fixture']:
        path=lane/name
        if path.exists():shutil.rmtree(path);deleted.append(str(path))
    caches=[]
    root=Path('/home/helena/.aibuildcaches')
    if root.exists():
        for p in root.iterdir():
            if 'lane229' in p.name:
                if p.is_dir():shutil.rmtree(p)
                else:p.unlink()
                caches.append(str(p))
    manifest={'deleted':deleted,'deletedLaneCaches':caches,'remainingLaneEntries':[p.name for p in lane.iterdir()]}
    (out/'r229-files-cleanup.json').write_text(json.dumps(manifest,indent=2)); print(json.dumps(manifest)); assert not manifest['remainingLaneEntries']
else:raise ValueError(sys.argv[1])
