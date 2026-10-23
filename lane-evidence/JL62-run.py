import json, os, shutil, subprocess, sys, time
from pathlib import Path
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
root=Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/JL62')
out=root.parent.parent/'out'
started_at=time.time()
phase=sys.argv[1]
name='JL62-'+phase
cmd=['./gradlew','--no-daemon']
if phase.startswith('full'):
    cmd=['flock','-w',('540' if phase=='full3' else ('600' if phase!='full' else '5400')),'/tmp/claude-501/complete-suite.lock','timeout','45m']+cmd+['cleanTest','test','--continue']
else:
    cmd+=[':libp2p:cleanTest',':libp2p:test']
    selectors=['io.libp2p.mux.PausedChildResumeReadReentryTcpTest'] if phase=='reentry-red' else ['io.libp2p.mux.PausedChild*Test']
    if phase.startswith('neighbours'):
        selectors += ['io.libp2p.mux.ChildReadPauseTcpTest','io.libp2p.mux.ChildPauseSiblingStreamTcpTest','io.libp2p.mux.yamux.YamuxPausedQueueBoundsTest','io.libp2p.mux.yamux.YamuxResumeAtFrameBound*TcpTest','io.libp2p.mux.yamux.PausedYamux*BackingStorageTest','io.libp2p.mux.yamux.YamuxPaused*TcpTest','io.libp2p.mux.StreamCloseWrite*Test','io.libp2p.etc.util.netty.AbstractChildChannel*Test','io.libp2p.etc.util.netty.ChildChannelTeardownIsUnconditionalTest','io.libp2p.core.ParentCloseListenerReviewTest','io.libp2p.core.HostStreamVisitorCloseTest','io.libp2p.core.MuxCloseDuringPublicationTest']
    for selector in selectors:cmd += ['--tests',selector]
    cmd=['timeout','25m']+cmd
with (out/(name+'.log')).open('w') as log, (out/(name+'-memory.jsonl')).open('w') as memory:
    p=subprocess.Popen(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    print(json.dumps({'phase':phase,'pid':p.pid,'command':cmd}),flush=True)
    last=0;start=time.monotonic()
    while p.poll() is None:
        if time.monotonic()-last>=120:
            sample={'utc':datetime.now(timezone.utc).isoformat(),'memory_current':int(Path('/sys/fs/cgroup/memory.current').read_text()),'memory_events':Path('/sys/fs/cgroup/memory.events').read_text()}
            memory.write(json.dumps(sample)+'\n');memory.flush();last=time.monotonic()
            print(json.dumps(sample),flush=True)
        if time.monotonic()-start>8500:
            os.killpg(p.pid,15);p.wait(timeout=30)
            raise RuntimeError('JL62 command exceeded its hard deadline')
        time.sleep(2)
    code=p.wait()
results=out/(name+'-results');results.mkdir(exist_ok=True)
summary={'phase':phase,'exit':code,'tests':0,'failures':0,'errors':0,'skipped':0,'failure_identities':[],'elapsed_seconds':round(time.monotonic()-start,2)}
for xml in (root.glob('**/build/test-results/test/TEST-*.xml') if phase.startswith('full') else (root/'libp2p/build/test-results/test').glob('TEST-*.xml')):
    if xml.stat().st_mtime<started_at:
        continue
    dest=results/str(xml.relative_to(root)).replace('/','__');shutil.copy2(xml,dest)
    suite=ET.parse(xml).getroot()
    for key in ['tests','failures','errors','skipped']:summary[key]+=int(suite.get(key,0))
    for case in suite.findall('testcase'):
        if case.find('failure') is not None or case.find('error') is not None:summary['failure_identities'].append([case.get('classname'),case.get('name')])
(out/(name+'.json')).write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary),flush=True)
sys.exit(code)
