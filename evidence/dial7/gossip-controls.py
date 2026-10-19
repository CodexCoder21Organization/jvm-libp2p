import subprocess, pathlib, datetime, json, os, signal, shutil, time
root=pathlib.Path.cwd(); ev=root/'evidence'; findings=pathlib.Path('/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/out/flake-urlprotocol-dial7-findings.md')
lock='/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock'
rows=[]
def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
for head,folder in [('before','fork-before'),('merged','fork')]:
    repo=root/folder
    for i in range(1,11):
        dest=ev/f'gossip-{head}-{i:02d}'; dest.mkdir(exist_ok=True)
        cmd=['flock',lock,'bash','-c','date -u +ACQUIRED=%Y-%m-%dT%H:%M:%SZ; exec "$@"','gossip-control','./gradlew','--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m','-Pkotlin.compiler.execution.strategy=in-process','--init-script',str(ev/'force-test.gradle'),':libp2p:test','--tests','io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame']
        start=now(); print(f'{start} WAIT {head} {i}/10',flush=True)
        with (dest/'gradle.log').open('w') as log:
            log.write('WAIT='+start+'\n'); log.flush()
            p=subprocess.Popen(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            try: rc=p.wait(timeout=900)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid,signal.SIGTERM)
                try: p.wait(timeout=10)
                except subprocess.TimeoutExpired: os.killpg(p.pid,signal.SIGKILL); p.wait()
                raise RuntimeError(f'PROBLEM control exceeded 900 seconds {head} {i}')
        xmls=list((repo/'libp2p/build/test-results/test').glob('TEST-*.xml'))
        import xml.etree.ElementTree as ET
        tests=[]
        for x in xmls:
            shutil.copy2(x,dest/x.name)
            for t in ET.parse(x).getroot().iter('testcase'):
                tests.append({'class':t.get('classname'),'name':t.get('name'),'duration':t.get('time'),'status':'FAILED' if t.find('failure') is not None else 'SKIPPED' if t.find('skipped') is not None else 'PASSED','stack':t.findtext('failure')})
        if len(tests)!=1 or tests[0]['class']!='io.libp2p.pubsub.gossip.GossipTwoHostTest': raise RuntimeError(f'PROBLEM unexpected test records {head} {i}: {tests}')
        row={'head':head,'iteration':i,'attempt':1,'started':start,'ended':now(),'gradle_exit':rc,**tests[0]}; rows.append(row)
        (ev/'gossip-attempts.json').write_text(json.dumps(rows,indent=2))
        print(json.dumps({k:v for k,v in row.items() if k!='stack'}),flush=True)
        with findings.open('a') as f: f.write(f'\nOBSERVED {now()}: Gossip {head} run {i}/10: attempt 1 {row["status"]}, {row["duration"]}s, Gradle exit {rc}. Native XML and full log retained in evidence/gossip-{head}-{i:02d}.\n')
        if rc not in (0,1) or (rc==1 and row['status']!='FAILED'): raise RuntimeError('PROBLEM Gradle failed outside test assertion')
print('CONTROL_TERMINAL '+json.dumps({h:sum(r['status']=='FAILED' for r in rows if r['head']==h) for h in ['before','merged']}),flush=True)
