from pathlib import Path
import subprocess, time, os, signal, shutil, json, xml.etree.ElementTree as ET
out=Path(__file__).resolve().parent
repo=out.parent/'jvm-libp2p'
findings=repo.parent.parent.parent/'out/F68g-findings.md'
# Begin only after the sequential required class register is complete.
import datetime
end=datetime.datetime(2026,10,24,7,7,0,tzinfo=datetime.timezone.utc).timestamp()
assert (out/'classes-done').exists(), 'Required first-attempt sequence not complete'
paths=subprocess.check_output(['git','diff','--name-only','origin/develop...HEAD','--','libp2p/src/main'],cwd=repo,text=True).splitlines()
original={p:(repo/p).read_bytes() for p in paths}
backup=out/'original-production'
for p,data in original.items():
    target=backup/p
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(data)
try:
    for p in paths:
        (repo/p).write_bytes(subprocess.check_output(['git','show','origin/develop:'+p],cwd=repo))
    (out/'reverted-production.diff').write_bytes(subprocess.check_output(['git','diff'],cwd=repo))
    with findings.open('a') as f:f.write('\nOBSERVED: Reverted all six production files to origin/develop, retaining unchanged exact-head test sources and compiled test/test-fixture outputs. Four test-compilation tasks excluded because unrelated Lifecycle classes call newly-added Transport.ready; selected Traffic/Binding do not call that added method. Production recompiles from actual base bytes.\n')
    for cls in ['NoisePreparationTrafficTest','NoiseBindingReadinessTest']:
        label='reverted-'+cls;d=out/label;d.mkdir(exist_ok=True)
        cmd=['./gradlew','-g',str(out/'gradle-home'),':libp2p:test','--tests','io.libp2p.security.noise.'+cls]
        for task in ['compileTestKotlin','compileTestJava','compileTestFixturesKotlin','compileTestFixturesJava']:
            cmd+=['-x',':libp2p:'+task]
        subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
        subprocess.run(['git','rebase','--autostash','origin/develop'],cwd=repo,check=True)
        (d/'command.json').write_text(json.dumps(cmd));started=time.time()
        with (d/'console.log').open('w') as log:
            p=subprocess.Popen(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            (out/'current-control-process.json').write_text(json.dumps({'pid':p.pid,'class':cls,'start':started}))
            try:rc=p.wait(timeout=min(900,max(1,end-time.time())))
            except subprocess.TimeoutExpired:
                os.killpg(p.pid,signal.SIGTERM)
                try:p.wait(timeout=10)
                except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
                rc=124
        record={'label':label,'class':cls,'exit':rc}
        xml=repo/'libp2p/build/test-results/test'/('TEST-io.libp2p.security.noise.'+cls+'.xml')
        if xml.exists() and xml.stat().st_mtime>=started-2:
            shutil.copy2(xml,d/xml.name);root=ET.parse(xml).getroot();record.update(root.attrib);record['ms']=round(float(root.get('time'))*1000)
            record['cases']=[c.attrib for c in root.findall('testcase')]
            with findings.open('a') as f:
                f.write('\nOBSERVED: Native reverted-production XML '+json.dumps(record)+' first control attempt.\n')
                for c in root.findall('testcase'):
                    for fail in list(c.findall('failure'))+list(c.findall('error')):
                        f.write('\nFull control failure '+c.get('name')+':\n```text\n'+(fail.text or '')+'\n```\n')
        else:
            record['xml']='absent'
            with findings.open('a') as f:f.write('\nOBSERVED: '+json.dumps(record)+'\n')
        (d/'result.json').write_text(json.dumps(record,indent=2));print(json.dumps(record),flush=True)
finally:
    for p,source in original.items():(repo/p).write_bytes(source)
    (out/'restored-after-reversal').write_text(subprocess.check_output(['git','status','--short'],cwd=repo,text=True))
    with findings.open('a') as f:f.write('\nOBSERVED: Restored every production source byte to exact HEAD after reversal; status='+subprocess.check_output(['git','status','--short'],cwd=repo,text=True).strip()+'\n')
(out/'controls-done').write_text('done')
