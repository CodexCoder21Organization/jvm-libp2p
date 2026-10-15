from pathlib import Path
import os, subprocess, sys, shutil, xml.etree.ElementTree as E, json
name=sys.argv[1]
repo=Path('jvm-libp2p')
path=repo/'libp2p/src/main/kotlin/io/libp2p/pubsub/SeenCache.kt'
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
original=path.read_bytes(); text=original.decode()
if name=='canonical':
 old='val slowId = fastIdMap[fastIdFunction(msg)] ?: msg.messageId'
 new='val slowId = msg.messageId'
elif name=='eager-canonical':
 old='val slowId = fastIdMap[fastIdFunction(msg)] ?: msg.messageId'
 new='msg.messageId\n        '+old
else: raise ValueError(name)
assert text.count(old)==1
stage='mutation-'+name
out=Path('evidence')/stage
out.mkdir(exist_ok=True)
try:
 path.write_text(text.replace(old,new))
 patch=subprocess.check_output(['git','diff','--','libp2p/src/main/kotlin/io/libp2p/pubsub/SeenCache.kt'],cwd=repo)
 Path('evidence',stage+'.patch').write_bytes(patch)
 results=repo/'libp2p/build/test-results/test'
 if results.exists(): shutil.rmtree(results)
 cmd=['jvm-libp2p/gradlew','-p','jvm-libp2p','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx2g','-Pkotlin.compiler.execution.strategy=in-process',':libp2p:test','--tests','io.libp2p.pubsub.GossipApiTest.testFastMessageId']
 code=subprocess.run([sys.executable,'bounded.py','900',f'evidence/{stage}.log']+cmd,env=dict(os.environ,JAVA_OPTS='-Xmx2g')).returncode
 if results.exists(): shutil.copytree(results,out/'test',ignore=shutil.ignore_patterns('binary'),dirs_exist_ok=True)
 xml=out/'test/TEST-io.libp2p.pubsub.GossipApiTest.xml'
 if not xml.exists(): raise RuntimeError(f'{stage}: no native test XML, command exit {code}')
 r=E.parse(xml).getroot()
 print(json.dumps({'stage':stage,'commandExit':code,'tests':r.get('tests'),'failures':r.get('failures')}),flush=True)
 assert code==1 and r.get('tests')=='1' and r.get('failures')=='1'
 print(r.find('.//failure').text,flush=True)
finally:
 path.write_bytes(original)
 assert subprocess.check_output(['git','diff','--','libp2p/src/main/kotlin/io/libp2p/pubsub/SeenCache.kt'],cwd=repo)==b''
