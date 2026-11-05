from pathlib import Path
import subprocess,sys,json,time,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[3]
repo=root/'jvm-libp2p'; build=root/'f93c-build'; evidence=Path(__file__).resolve().parent
slot=evidence/'with-slot.sh';java='/opt/java/bin/java'; console='/tmp/flibp6-junit-console.jar'
mode,action,*args=sys.argv[1:]
cp=(build/'classpath').read_text()
if action=='compile':
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True).strip())
    if dirty: subprocess.run(['git','stash','push','-u','-m','F93b build rebase'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
    subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
    subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
    if dirty: subprocess.run(['git','stash','pop'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
    command=[java,'-cp',(build/'compiler-classpath').read_text(),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-language-version','1.6','-Xjvm-default=all','-Xfriend-paths='+str(build/'snapshot45.jar'),'-classpath',cp+':'+console,'-d',str(build/mode)]+args
else:
    overlays, *classes=args
    command=[java,'-jar',console,'execute','--class-path',':'.join(str(build/x) for x in overlays.split(','))+':'+cp,'--reports-dir',str(evidence/mode),'--fail-if-no-tests']
    for c in classes:command+=['--select-class','io.libp2p.transport.'+c]
start=time.time()
with (evidence/(mode+'.log')).open('w') as log:
    proc=subprocess.Popen([str(slot),'timeout','600']+command,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
    (build/'active-pid').write_text(str(proc.pid))
    try:code=proc.wait(timeout=2110)
    except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=30);raise
    finally:(build/'active-pid').unlink(missing_ok=True)
(evidence/(mode+'.exit')).write_text(str(code)+'\n')
summary={'exit':code,'seconds':round(time.time()-start,3),'cases':[],'attempts':0,'passed':0,'failed':0,'errors':0,'skipped':0}
for xml in (evidence/mode).glob('*.xml'):
    t=ET.parse(xml)
    for parent in t.iter():
        for child in list(parent):
            if child.tag=='properties':parent.remove(child)
    for c in t.iter('testcase'):
        failure=c.find('failure');error=c.find('error');skip=c.find('skipped')
        summary['attempts']+=1
        summary['failed']+=failure is not None;summary['errors']+=error is not None;summary['skipped']+=skip is not None
        summary['passed']+=failure is None and error is None and skip is None
        summary['cases'].append({'class':c.get('classname'),'name':c.get('name'),'failure':None if failure is None else failure.get('message')})
    t.write(xml,encoding='unicode')
(evidence/(mode+'-summary.json')).write_text(json.dumps(summary,indent=2))
print(json.dumps({k:v for k,v in summary.items() if k!='cases'}),flush=True)
sys.exit(code)
