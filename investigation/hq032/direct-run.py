from pathlib import Path
import os,subprocess,shlex,hashlib,xml.etree.ElementTree as E
repo=Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol')
out=Path('/home/u/lanes/hd27/ws/hq032/direct-phase');out.mkdir(exist_ok=True)
retained=[p for p in Path('/home/u/lanes/hd27/out/hq032-direct-preserved-cp.txt').read_text().split(os.pathsep) if not Path(p).name.startswith('protocol-0.0.')]
root=Path('/home/u/.aibuildcaches/_home_u_lanes_hd27_ws_hq023_workspace_UrlProtocol')
protocol=max(root.glob('artifactBinaryCache/**/protocol-0.0.1442.jar'),key=lambda p:p.stat().st_mtime)
classpath=os.pathsep.join([str(protocol),*retained])
source=(repo/'tests/stressTestRejectedDrainConcurrentHostStops.kts').read_text()
source='\n'.join(x for x in source.splitlines() if not x.startswith('@file:WithArtifact(') and x!='import build.kotlin.withartifact.WithArtifact')+'\n'
(out/'stressTestRejectedDrainConcurrentHostStops.kt').write_text(source)
launcher=(repo/'jars/KompileCli.jar').read_text()
compiler_cp=next(x[len("CLASSPATH='"):-1] for x in launcher.splitlines() if x.startswith("CLASSPATH='"))
classes=out/'classes';classes.mkdir(exist_ok=True)
subprocess.run(['java','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-classpath',classpath,'-d',str(classes),str(out/'stressTestRejectedDrainConcurrentHostStops.kt')],check=True,timeout=180)
java=out/'RunPhaseStress.java';java.write_text('public class RunPhaseStress { public static void main(String[] args) { foundation.url.protocol.StressTestRejectedDrainConcurrentHostStopsKt.stressTestRejectedDrainConcurrentHostStops(); } }\n')
classpath=str(classes)+os.pathsep+classpath
subprocess.run(['javac','-cp',classpath,str(java)],check=True,timeout=60)
(out/'classpath.txt').write_text(classpath)
(out/'inputs.txt').write_text('source_head='+subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True)+''.join(str(p)+' sha256='+hashlib.sha256(Path(p).read_bytes()).hexdigest()+'\n' for p in [protocol,*retained]))
r=E.Element('component-tests')
for n in range(1,11):
 log=Path(f'/home/u/lanes/hd27/out/hq032-direct-phase-{n}.log')
 with log.open('wb') as f:
  result=subprocess.run(['java','-ea','-Xmx128m','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-XX:TieredStopAtLevel=1','-Dio.netty.eventLoopThreads=1','-Dfoundation.url.test.isolated=true','-Djava.security.egd=file:/dev/urandom','-cp',str(out)+os.pathsep+classpath,'RunPhaseStress'],stdout=f,stderr=subprocess.STDOUT,timeout=240)
 text=log.read_text();passed=result.returncode==0 and 'FL635_CYCLES completed=128 failed=0 concurrency=8 iterations=128' in text
 E.SubElement(r,'test',number=str(n),success=str(passed).lower(),exit=str(result.returncode))
 E.ElementTree(r).write('/home/u/lanes/hd27/out/hq032-direct-phase.xml',encoding='unicode')
 print('Direct component phase proof',n,'exit',result.returncode,'passed',passed,flush=True)
 if not passed:raise SystemExit(1)
