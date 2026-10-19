from pathlib import Path
import subprocess,datetime,concurrent.futures,json,os,signal
b=Path(__file__).resolve().parent
print('ACQUIRED',datetime.datetime.now(datetime.timezone.utc).isoformat(),flush=True)
compiler=(b/'compiler-classpath.txt').read_text().strip()
fixed=b/'n3-fixed.jar'
old=(b/'runtime-classpath.txt').read_text().strip().split(':')
old=[str(fixed) if '/jvm-libp2p/' in p else p for p in old]
console=Path('/home/lynn/.cache/coursier/v1/https/maven-central.storage-download.googleapis.com/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar')
runtime=str(b/'protocol-main.jar')+':'+':'.join(old)+':'+str(console)
sources=sorted((b/'local-four').glob('*.kt'))
subprocess.run(['java','-Xmx256m','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',runtime,'-d',str(b/'four-classes')]+list(map(str,sources)),check=True,timeout=180)
cp=str(b/'four-classes')+':'+runtime
names=['FourOriginal','FourOne','FourTwo','FourThree']
def run(name):
 with (b/f'four-{name}.log').open('w') as log:
  args=['java','-Xmx128m','-XX:+UseSerialGC','-Dfoundation.url.test.isolated=true','-cp',cp,'org.junit.platform.console.ConsoleLauncher','execute','--select-class','foundation.url.protocol.'+name,'--reports-dir',str(b/f'four-{name}-native'),'--disable-banner']
  p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:code=p.wait(timeout=90)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait();code='TIMEOUT'
 return dict(name=name,code=code)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as x:r=list(x.map(run,names))
(b/'four-results.json').write_text(json.dumps(r,indent=2));print(r,flush=True)
