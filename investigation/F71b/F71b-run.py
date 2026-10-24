import json, pathlib, shutil, subprocess, sys, time, xml.etree.ElementTree as ET
out=pathlib.Path(__file__).parent
repo=out.parent/'ws/F71b/jvm-libp2p'
label, cls=sys.argv[1:3]
with (out/f'F71b-{label}.log').open('w') as log:
    for cmd in (['git','fetch','origin'], ['git','rebase','--autostash','origin/develop']):
        subprocess.run(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
    start=time.time()
    cmd=['timeout','--signal=TERM','--kill-after=20s','600s','./gradlew',':libp2p:test','--tests',cls,'--console=plain']
    log.write('COMMAND: '+' '.join(cmd)+'\n');log.flush()
    result=subprocess.run(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
    elapsed=round((time.time()-start)*1000)
report=repo/'libp2p/build/test-results/test'/f'TEST-{cls}.xml'
record={'label':label,'class':cls,'exit':result.returncode,'wall_ms':elapsed,'xml':None}
if report.exists() and report.stat().st_mtime>=start:
    dest=out/f'F71b-{label}.xml';shutil.copy2(report,dest)
    root=ET.parse(dest).getroot()
    record.update(xml=dest.name,total=int(root.attrib['tests']),failures=int(root.attrib['failures']),errors=int(root.attrib['errors']),skipped=int(root.attrib['skipped']),test_ms=round(float(root.attrib['time'])*1000))
(out/f'F71b-{label}.json').write_text(json.dumps(record,indent=2)+'\n')
with (out/'F71b-findings.md').open('a') as findings:
    findings.write('\nOBSERVED: Test record '+json.dumps(record)+'\n')
    if record.get('failures') or record.get('errors'):
        findings.write('Full failure record: '+f'F71b-{label}.xml and F71b-{label}.log'+'\n')
print(json.dumps(record))
sys.exit(result.returncode)
