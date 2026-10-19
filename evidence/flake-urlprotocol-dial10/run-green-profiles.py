from pathlib import Path
import subprocess,datetime,concurrent.futures,json,os,signal
b=Path(__file__).resolve().parent
print('ACQUIRED',datetime.datetime.now(datetime.timezone.utc).isoformat(),flush=True)
old=(b/'runtime-classpath.txt').read_text().strip().split(':')
old=[str(b/'n3-fixed.jar') if '/jvm-libp2p/' in p else p for p in old]
r=str(b/'protocol-main.jar')+':'+':'.join(old)
compiler=(b/'compiler-classpath.txt').read_text().strip()
subprocess.run(['java','-Xmx256m','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',r,'-d',str(b/'phase-classes'),str(b/'PhaseDialProbe.kt')],check=True,timeout=120)
cp=str(b/'phase-classes')+':'+r
def run(i):
 with (b/f'fixed-{i}.log').open('w') as log:
  args=['java','-Xmx128m','-XX:+UseSerialGC',f'-XX:StartFlightRecording=filename={b}/fixed-{i}.jfr,settings={b}/dial.jfc,dumponexit=true','--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED','--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED',f'-javaagent:{b}/agent-minimal.jar','-Dfoundation.url.test.isolated=true','-cp',cp,'PhaseDialProbeKt']
  p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=p.wait(timeout=60)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait();code='TIMEOUT'
 return dict(index=i,code=code)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as x:r=list(x.map(run,range(4)))
(b/'fixed-results.json').write_text(json.dumps(r,indent=2));print(r,flush=True)
