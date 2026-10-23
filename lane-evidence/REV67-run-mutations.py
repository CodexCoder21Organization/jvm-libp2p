from pathlib import Path
import datetime,json,shutil,subprocess,time,xml.etree.ElementTree as E
out=Path('../../out').resolve()
for run in range(1,4):
    for command in [['git','fetch','origin'],['git','rebase','origin/develop']]:
        subprocess.run(command,check=True,timeout=90)
    phase=f'REV67-mutant-{run}'
    started=time.monotonic()
    command=['timeout','--signal=TERM','--kill-after=15s','15m','./gradlew','--no-daemon',':libp2p:cleanTest',':libp2p:test','--tests','io.libp2p.transport.ConnectionCloseRetryTest','--tests','io.libp2p.core.RelayConnectionRetryTest']
    samples=[]
    with (out/(phase+'.log')).open('w') as log:
        process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
        print(phase,'pid',process.pid,flush=True)
        next_sample=0
        while process.poll() is None:
            if time.monotonic()>=next_sample:
                sample={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'memory_current':int(Path('/sys/fs/cgroup/memory.current').read_text()),'events':Path('/sys/fs/cgroup/memory.events').read_text()}
                samples.append(sample); print(phase,sample,flush=True);next_sample=time.monotonic()+120
            time.sleep(1)
    result={'exit':process.returncode,'elapsed':time.monotonic()-started,'memory_samples':samples,'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'command':command}
    dest=out/(phase+'-results');dest.mkdir(exist_ok=True)
    cases=[]
    for xml in Path('libp2p/build/test-results/test').glob('*.xml'):
        shutil.copy2(xml,dest/xml.name)
        root=E.parse(xml).getroot()
        for c in root.findall('testcase'):
            cases.append({'class':c.get('classname'),'name':c.get('name'),'failed':c.find('failure') is not None,'error':c.find('error') is not None,'skipped':c.find('skipped') is not None})
    result['cases']=cases
    (out/(phase+'.json')).write_text(json.dumps(result,indent=2))
    failures=[c for c in cases if c['failed'] or c['error']]
    print(phase,'exit',process.returncode,'cases',len(cases),'failed',len(failures),'elapsed',result['elapsed'],flush=True)
    if process.returncode!=1 or len(cases)!=11 or len(failures)!=9 or any(c['skipped'] for c in cases):
        raise SystemExit('Unexpected mutation outcome: inspect logs; no automatic repetition.')
