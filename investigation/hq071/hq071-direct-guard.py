from pathlib import Path
import subprocess,os,sys,json,hashlib,time,signal
repo=Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol');out=Path('/home/u/lanes/hd27/out')
mode,label=sys.argv[1:3];repeats=int(sys.argv[3]) if len(sys.argv)>3 else 1
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','origin/main'],cwd=repo,check=True)
name='testHostStopObservesCompletedCloseWithFullEventLoop' if mode in ('queue','normal') else 'stressTestRejectedDrainConcurrentHostStops'
work=Path('/home/u/lanes/hd27/ws/hq071/component')/label;work.mkdir(parents=True,exist_ok=True)
source=(repo/'tests'/f'{name}.kts').read_text();original=source
if mode=='queue':
 before='System.setProperty("io.netty.eventLoop.maxPendingTasks", "1024")';assert source.count(before)==1
 source=source.replace(before,'System.setProperty("io.netty.eventLoop.maxPendingTasks", "8192")')
if mode=='stress-fault':
 before='assertTrue(releaseLoop.await(10, TimeUnit.SECONDS), "The test must release the root loop")';assert source.count(before)==1
 source=source.replace(before,before+'\n            check(false) { "hq071 controlled held-loop callback failure" }')
if mode=='phase':
 before='        val outbound = activeRoot.unsafe().outboundBuffer()';assert source.count(before)==1
 source=source.replace(before,'        check(activeRoot.eventLoop().parent().count() == 4) { "The phase control must use exactly four shared NIO loops" }\n'+before)
source='\n'.join(x for x in source.splitlines() if not x.startswith('@file:WithArtifact(') and x!='import build.kotlin.withartifact.WithArtifact')+'\n'
kt=work/f'{name}.kt';kt.write_text(source)
(out/f'{label}-source.kt.txt').write_text(source)
classpath=(out/'hq071-component-cp.txt').read_text()
launcher=(repo/'jars/KompileCli.jar').read_text()
compiler_cp=next(x[len("CLASSPATH='"):-1] for x in launcher.splitlines() if x.startswith("CLASSPATH='"))
classes=work/'classes';classes.mkdir(exist_ok=True)
java=work/'RunGuard.java';fileclass=name[0].upper()+name[1:]+'Kt'
java.write_text('public class RunGuard { public static void main(String[] args) { foundation.url.protocol.'+fileclass+'.'+name+'(); } }\n')
meta={'mode':mode,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'sourceSha256':hashlib.sha256(original.encode()).hexdigest(),'compiledSourceSha256':hashlib.sha256(source.encode()).hexdigest(),'producerSource':'cc33cf0df81e5980d9cc80234a393192ecef392e','producerJarSha256':'3f02b8277487ec7fe701ab90476678699691a0578f63b8659ea75ae0c5f03b23','started':time.time(),'runs':[]}
try:
 with (out/f'{label}-compile.log').open('w') as log:
  subprocess.run(['java','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-classpath',classpath,'-d',str(classes),str(kt)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=180)
  subprocess.run(['javac','-cp',str(classes)+os.pathsep+classpath,str(java)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=60)
 cp=str(work)+os.pathsep+str(classes)+os.pathsep+classpath
 for i in range(1,repeats+1):
  cmd=['java','-ea','-Xmx128m','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-XX:TieredStopAtLevel=1','-Dfoundation.url.test.isolated=true','-Djava.security.egd=file:/dev/urandom']
  if mode in ('phase','stress-fault'):cmd+=['-Dio.netty.eventLoopThreads=4']
  cmd+=['-cp',cp,'RunGuard'];start=time.monotonic()
  with (out/f'{label}-{i}.log').open('w') as log:
   child=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   try:code=child.wait(timeout=30 if name.startswith('testHost') else 240)
   except subprocess.TimeoutExpired:
    os.killpg(child.pid,signal.SIGTERM)
    try:child.wait(timeout=10)
    except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
    code=124
  meta['runs'].append({'number':i,'exit':code,'seconds':time.monotonic()-start})
  (out/f'{label}-meta.json').write_text(json.dumps(meta,indent=2))
finally:
 meta['ended']=time.time();(out/f'{label}-meta.json').write_text(json.dumps(meta,indent=2))
print(json.dumps(meta,indent=2))
sys.exit(0 if all(x['exit']==0 for x in meta['runs']) and len(meta['runs'])==repeats else 1)
