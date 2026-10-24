from pathlib import Path
import subprocess,time,json,os,signal,xml.etree.ElementTree as ET,shutil
root=Path(__file__).resolve().parent;repo=root.parent/'jvm-libp2p';end=time.monotonic()+1200
classes=['NoisePreparationLifecycleTest','NoisePreparationConcurrencyTest','NoisePreparationFailureTest','NoisePreparationTrafficTest','NoiseHandshakeTest','NetworkPendingConnectTest','NoiseAlgorithmReadinessTest','NoiseMatcherReadinessTest','NoisePreparationReentryTest','NoiseProbePhasesTest']
for cls in classes:
 if time.monotonic()>=end:raise SystemExit('PROBLEM: class register hard deadline')
 d=root/('class-'+cls);d.mkdir()
 pkg='io.libp2p.network.' if cls=='NetworkPendingConnectTest' else 'io.libp2p.security.noise.'
 subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
 subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
 cmd=['./gradlew','-g',str(root/'gradle-home'),':libp2p:test','--tests',pkg+cls,'--max-workers=2'];(d/'command.json').write_text(json.dumps(cmd))
 start=time.time()
 with (d/'console.log').open('w') as f:
  p=subprocess.Popen(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
  (root/'current-class-process.json').write_text(json.dumps({'class':cls,'pid':p.pid,'start':start}))
  try:rc=p.wait(timeout=min(300,max(1,end-time.monotonic())))
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGTERM)
   try:p.wait(timeout=5)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
   rc=124
 (d/'exit.json').write_text(json.dumps({'exit':rc,'start':start}))
 xml=repo/'libp2p/build/test-results/test'/('TEST-'+pkg+cls+'.xml')
 if not xml.exists() or xml.stat().st_mtime<start-2:raise SystemExit('PROBLEM: no fresh native XML for '+cls)
 subprocess.run(['python3',str(root/'archive.py'),str(d)],check=True)
(root/'classes-done').write_text('done')
