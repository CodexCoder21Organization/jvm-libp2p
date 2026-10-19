import pathlib,subprocess,datetime,signal,os,sys
root=pathlib.Path(__file__).resolve().parents[1]; log=root/'evidence/fork-publication-build.log'
cmd=['flock','/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock','bash','-c','date -u +ACQUIRED=%Y-%m-%dT%H:%M:%SZ; exec "$@"','publish-build','./gradlew','--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m','-Pkotlin.compiler.execution.strategy=in-process',':libp2p:publishToMavenLocal','-PmavenArtifactId=jvm-libp2p']
with log.open('w') as f:
 f.write('WAIT='+datetime.datetime.now(datetime.timezone.utc).isoformat()+'\n'); f.flush()
 p=subprocess.Popen(cmd,cwd=root/'fork',stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
 try:rc=p.wait(timeout=900)
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
  raise SystemExit('PROBLEM publication build exceeded 900 seconds')
print('PUBLICATION_BUILD_TERMINAL',rc);sys.exit(rc)
