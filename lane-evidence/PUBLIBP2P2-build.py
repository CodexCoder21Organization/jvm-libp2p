import datetime, json, os, pathlib, signal, subprocess, sys, time
S = pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad')
repo = S / 'workspace/PUBLIBP2P2'
log = S / 'out/PUBLIBP2P2-build.log'
memlog = S / 'out/PUBLIBP2P2-build-memory.jsonl'
child = None

def sample(phase):
    current = int(pathlib.Path('/sys/fs/cgroup/memory.current').read_text())
    stats = {k:int(v) for k,v in (line.split() for line in pathlib.Path('/sys/fs/cgroup/memory.stat').read_text().splitlines())}
    clean_inactive_file = max(0, stats['inactive_file'] - stats.get('file_dirty',0) - stats.get('file_writeback',0))
    working_set = current - clean_inactive_file
    record = dict(time=datetime.datetime.now(datetime.timezone.utc).isoformat(), phase=phase, memory_current=current, clean_inactive_file=clean_inactive_file, working_set=working_set)
    with memlog.open('a') as f: f.write(json.dumps(record) + '\n')
    print(json.dumps(record), flush=True)
    return working_set

def stop(signum, frame):
    if child and child.poll() is None:
        os.killpg(child.pid, signal.SIGTERM)
        try: child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait()
    sys.exit(128 + signum)
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
maximum = int(pathlib.Path('/sys/fs/cgroup/memory.max').read_text())
start = time.monotonic()
while maximum - sample('memory-admission') < 2 * 1024**3:
    if time.monotonic() - start > 1200:
        print('PROBLEM: no 2 GiB headroom after clean inactive file cache within 20 minutes; no build started', flush=True)
        sys.exit(75)
    time.sleep(30)
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip() == 'bd7edb2570fb609c20245305c25af29d166a67c6'
assert subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True) == ''
cmd = ['./gradlew','--no-daemon','--max-workers=1','--console=plain','-I',str(S/'out/PUBLIBP2P2-release.init.gradle'),'-PmavenArtifactId=jvm-libp2p',':libp2p:jar',':libp2p:generatePomFileForMavenJavaPublication']
print('START: ' + ' '.join(cmd), flush=True)
with log.open('w') as f:
    child = subprocess.Popen(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
    print('BUILD_PROCESS_GROUP=' + str(child.pid), flush=True)
    while child.poll() is None:
        sample('building')
        try: child.wait(timeout=30)
        except subprocess.TimeoutExpired: pass
    sample('finished')
    print('BUILD_EXIT=' + str(child.returncode), flush=True)
    sys.exit(child.returncode)
