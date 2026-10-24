import subprocess, pathlib, time, os, json, shutil, signal, sys
root=pathlib.Path(__file__).resolve().parent
repo=root.parent/'jvm-libp2p';folder=root/sys.argv[1];folder.mkdir()
cp=(root/'classpath.txt').read_text();cls='io.libp2p.security.noise.NoiseBindingReadinessTest'
sig=folder/'start';procs=[];handles=[]
def launch(cmd,name):
    h=open(folder/(name+'.log'),'w');handles.append(h)
    p=subprocess.Popen(cmd,cwd=repo,stdout=h,stderr=subprocess.STDOUT,start_new_session=True);procs.append(p);return p
try:
    subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
    subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
    g=launch(['./gradlew','-g',str(root/'gradle-home'),':libp2p:test','--tests',cls,'-I',str(root/'load-start.gradle'),'-Dnoise.load.signal='+str(sig),'--max-workers=2'],'gradle')
    deadline=time.monotonic()+600
    while not sig.exists():
        if g.poll() is not None: raise RuntimeError('Gradle ended before test launch')
        if time.monotonic()>deadline: raise TimeoutError('Gradle test launch deadline')
        time.sleep(.1)
    for n in range(7):
        launch(['/opt/java/bin/java','-Xmx256m','-Dio.netty.eventLoopThreads=1','-cp',str(root)+':'+cp,'RunNoiseClass',cls],'concurrent-'+str(n+1))
    deadline=time.monotonic()+240
    while any(p.poll() is None for p in procs):
        if time.monotonic()>deadline: raise TimeoutError('Concurrent class deadline')
        time.sleep(.1)
    result=[p.returncode for p in procs]
    for f in (repo/'libp2p/build/test-results/test').glob('TEST-*.xml'): shutil.copy2(f,folder/f.name)
    (folder/'exits.json').write_text(json.dumps(result));print('EXITS',result,flush=True)
finally:
    for p in procs:
        if p.poll() is None: os.killpg(p.pid,signal.SIGTERM)
    for p in procs:
        try:p.wait(timeout=5)
        except subprocess.TimeoutExpired: os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
    for h in handles:h.close()
