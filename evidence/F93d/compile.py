from pathlib import Path
import subprocess,sys,json,time
root=Path(__file__).resolve().parents[3]; repo=root/'jvm-libp2p'; b=root/'f93d-build'; e=Path(__file__).resolve().parent
mode=sys.argv[1]; files=sys.argv[2:]
subprocess.run(['git','fetch','origin'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
cmd=['/opt/java/bin/java','-cp',(b/'compiler-classpath').read_text(),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-language-version','1.6','-Xjvm-default=all','-Xfriend-paths='+str(b/'snapshot45.jar'),'-classpath',str(b/'baseline-all')+':'+(b/'classpath').read_text(),'-d',str(b/mode)]+files
start=time.time()
with (e/(mode+'.log')).open('w') as f:
 p=subprocess.Popen([str(e/'with-slots-early-deadline.sh'),'1','timeout','600']+cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT)
 (e/(mode+'.pid')).write_text(str(p.pid)); code=p.wait();(e/(mode+'.pid')).unlink()
(e/(mode+'.exit')).write_text(str(code)); print(mode,code,round(time.time()-start,2),flush=True)
sys.exit(code)
