from pathlib import Path
import os,subprocess,hashlib,xml.etree.ElementTree as E
repo=Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlResolver');base=Path('/home/u/lanes/hd27/ws/hq032/direct-phase')
out=base/'fresh';out.mkdir(exist_ok=True)
paths=Path('/home/u/lanes/hd27/out/hq032-direct-preserved-cp.txt').read_text().split(os.pathsep)
old=next(Path(p) for p in paths if Path(p).name=='protocol-0.0.1434.jar')
cache=Path('/home/u/.aibuildcaches/_home_u_lanes_hd27_ws_hq023_workspace_UrlProtocol')
new=max(cache.glob('artifactBinaryCache/**/protocol-0.0.1442.jar'),key=lambda p:p.stat().st_mtime)
common=[p for p in paths if not Path(p).name.startswith('protocol-0.0.')]
source=(repo/'tests/stressTestFreshJoinWithdrawalDeliveryUnderConcurrentReannounce.kts').read_text()
source='\n'.join(x for x in source.splitlines() if not x.startswith('@file:WithArtifact(') and x!='import build.kotlin.withartifact.WithArtifact')+'\n'
(out/'stressTestFreshJoinWithdrawalDeliveryUnderConcurrentReannounce.kt').write_text(source)
launcher=Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol/jars/KompileCli.jar').read_text();compiler=next(x[len("CLASSPATH='"):-1] for x in launcher.splitlines() if x.startswith("CLASSPATH='"))
classes=out/'classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join([str(new),*common])
subprocess.run(['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-classpath',cp,'-d',str(classes),str(out/'stressTestFreshJoinWithdrawalDeliveryUnderConcurrentReannounce.kt')],check=True,timeout=180)
j=out/'RunFresh.java';j.write_text('public class RunFresh { public static void main(String[] args) { foundation.url.resolver.StressTestFreshJoinWithdrawalDeliveryUnderConcurrentReannounceKt.stressTestFreshJoinWithdrawalDeliveryUnderConcurrentReannounce(); } }\n')
subprocess.run(['javac','-cp',str(classes)+os.pathsep+cp,str(j)],check=True,timeout=60)
r=E.Element('component-tests')
for name,protocol,expected in [('baseline',old,False),('fixed-1',new,True),('fixed-2',new,True),('fixed-3',new,True),('reversal',old,False)]:
 runtime=os.pathsep.join([str(out),str(classes),str(protocol),*common])
 log=Path('/home/u/lanes/hd27/out/hq032-direct-fresh-'+name+'.log')
 with log.open('wb') as f:
  result=subprocess.run(['java','-ea','-Xmx128m','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-XX:TieredStopAtLevel=1','-XX:ActiveProcessorCount=1','-Dfoundation.url.test.isolated=true','-Djava.security.egd=file:/dev/urandom','-cp',runtime,'RunFresh'],stdout=f,stderr=subprocess.STDOUT,timeout=900)
 text=log.read_text();passed=result.returncode==0
 E.SubElement(r,'test',name=name,protocol=str(protocol),success=str(passed).lower(),exit=str(result.returncode))
 E.ElementTree(r).write('/home/u/lanes/hd27/out/hq032-direct-fresh.xml',encoding='unicode')
 print(name,'exit',result.returncode,'expected-success',expected,flush=True)
 if passed!=expected:raise SystemExit('Unexpected comparison outcome')
 if not expected and 'WITHDRAWAL_NEVER_PROCESSED' not in text:raise SystemExit('Baseline failed on a different mechanism')
