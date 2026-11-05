from pathlib import Path
import subprocess,sys,json,time,hashlib,shutil
root=Path(__file__).resolve().parents[3]; repo=root/'jvm-libp2p'; b=root/'f93g-build'; e=Path(__file__).resolve().parent
mode=sys.argv[1]; files=sys.argv[2:]
subprocess.run(['git','fetch','origin'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
frozen=b/'source-snapshots'/mode
frozen.mkdir(parents=True,exist_ok=True)
manifest={'product_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'inputs':[]}
copied=[]
for index,source in enumerate(files):
    original=Path(source)
    if not original.is_absolute(): original=repo/original
    target=frozen/str(index)/original.name
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(original,target)
    copied.append(str(target))
    manifest['inputs'].append({'source':str(original),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
(e/(mode+'-inputs.json')).write_text(json.dumps(manifest,indent=2))
files=copied
cmd=['/opt/java/bin/java','-cp',(b/'compiler-classpath').read_text(),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-language-version','1.6','-Xjvm-default=all','-Xfriend-paths='+str(b/'snapshot45.jar'),'-classpath',str(b/'baseline-all')+':'+(b/'compile-classpath').read_text(),'-d',str(b/mode)]+files
start=time.time()
with (e/(mode+'.log')).open('w') as f:
 p=subprocess.Popen([str(e/('with-slots-cleanup-deadline.sh' if mode=='cleanup-final-frozen' else 'with-slots.sh')),'2','timeout','600']+cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT)
 (e/(mode+'.pid')).write_text(str(p.pid)); code=p.wait();(e/(mode+'.pid')).unlink()
(e/(mode+'.exit')).write_text(str(code)); print(mode,code,round(time.time()-start,2),flush=True)
sys.exit(code)
