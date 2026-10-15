import os, subprocess, sys, signal, time, json
seconds=int(sys.argv[1]); log=sys.argv[2]; command=sys.argv[3:]
start=time.monotonic(); owned=set()
def descendants():
    rows=subprocess.check_output(['ps','-eo','pid=,ppid=,pgid='],text=True)
    records=[tuple(map(int,row.split())) for row in rows.splitlines()]
    parents={p.pid}|{pid for pid,group in owned}
    changed=True
    while changed:
        changed=False
        for pid,ppid,pgid in records:
            if ppid in parents and pid not in parents:
                parents.add(pid); owned.add((pid,pgid)); changed=True
    owned.add((p.pid,p.pid))
def stop():
    for group in {g for _,g in owned}:
        try: os.killpg(group,signal.SIGTERM)
        except ProcessLookupError: pass
    time.sleep(1)
    for group in {g for _,g in owned}:
        try: os.killpg(group,signal.SIGKILL)
        except ProcessLookupError: pass
with open(log,'w') as f:
    f.write('COMMAND '+repr(command)+'\n'); f.flush()
    p=subprocess.Popen(command,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
    print('pid',p.pid,'log',log,flush=True)
    while p.poll() is None and time.monotonic()-start<seconds:
        descendants(); time.sleep(.25)
    timed=p.poll() is None
    stop(); code=124 if timed else p.returncode
    p.wait()
    result={'exit':code,'elapsed':time.monotonic()-start,'ownedProcesses':sorted(owned)}
    f.write('\nRESULT '+json.dumps(result)+'\n')
print(json.dumps(result),flush=True)
sys.exit(code)
