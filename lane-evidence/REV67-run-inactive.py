from pathlib import Path
import sys,subprocess,xml.etree.ElementTree as E,shutil,time,json
out=Path('../../out')
mode=sys.argv[1]
for run in [2,3]:
    subprocess.run(['git','fetch','origin'],check=True,timeout=90)
    subprocess.run(['git','rebase','origin/develop'],check=True,timeout=90)
    phase=f'REV67-inactive-{mode}-{run}'
    started=time.monotonic()
    command=['timeout','--signal=TERM','--kill-after=15s','15m','./gradlew','--no-daemon',':libp2p:cleanTest',':libp2p:test','--tests','io.libp2p.transport.ConnectionCloseRetryTest','--tests','io.libp2p.core.RelayConnectionRetryTest','--tests','io.libp2p.transport.RetryCloseInactiveReviewTest']
    before=int(Path('/sys/fs/cgroup/memory.current').read_text())
    with (out/(phase+'.log')).open('w') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    dest=out/(phase+'-results');dest.mkdir(exist_ok=True)
    counts=dict(tests=0,failures=0,errors=0,skipped=0)
    for p in Path('libp2p/build/test-results/test').glob('*.xml'):
        shutil.copy2(p,dest/p.name);root=E.parse(p).getroot()
        for k in counts:counts[k]+=int(root.get(k))
    metadata=dict(exit=result.returncode,elapsed=time.monotonic()-started,counts=counts,memory_before=before,memory_after=int(Path('/sys/fs/cgroup/memory.current').read_text()),events=Path('/sys/fs/cgroup/memory.events').read_text(),head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip())
    (out/(phase+'.json')).write_text(json.dumps(metadata,indent=2))
    print(phase,metadata,flush=True)
    expected=1 if mode=='shortcut' else 0
    if result.returncode!=expected or counts!={'tests':12,'failures':expected,'errors':0,'skipped':0}:raise SystemExit('Unexpected outcome; no more repeated runs.')
