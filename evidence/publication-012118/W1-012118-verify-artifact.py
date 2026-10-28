from pathlib import Path
import hashlib,json,sys,xml.etree.ElementTree as ET,zipfile
base=Path(sys.argv[1]); release=base/'release'; ev=base/'evidence'
v='4.2.10.1-codexcoder21-auto-read'; sha='c96e9dd2697ca1dc84ca423edc62bed6d204ae02'
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=ET.parse(release/f'netty-transport-{v}.pom').getroot()
assert pom.findtext('m:artifactId',namespaces=ns)=='netty-transport'
assert pom.findtext('m:version',namespaces=ns)==v
assert pom.findtext('m:parent/m:groupId',namespaces=ns)=='io.netty'
assert pom.findtext('m:parent/m:version',namespaces=ns)=='4.2.10.Final'
for dep in pom.findall('m:dependencies/m:dependency',ns):
 name=dep.findtext('m:artifactId',namespaces=ns)
 if name in ('netty-common','netty-buffer','netty-resolver','netty-testsuite-common'):
  assert dep.findtext('m:version',namespaces=ns)=='4.2.10.Final'
with zipfile.ZipFile('/tmp/W1-012118-original-netty.jar') as old,zipfile.ZipFile(release/f'netty-transport-{v}.jar') as new:
 oc={x for x in old.namelist() if x.endswith('.class')};nc={x for x in new.namelist() if x.endswith('.class')}
 assert not oc-nc,sorted(oc-nc)
 for name in old.namelist():
  if name.startswith(('META-INF/native-image/','META-INF/services/')) and not name.endswith('/'):
   assert old.read(name)==new.read(name),name
 assert b'Multi-Release: true' in new.read('META-INF/MANIFEST.MF')
 assert 'META-INF/versions/11/module-info.class' in new.namelist()
 props=new.read('META-INF/io.netty.versions.properties').decode()
 assert 'netty-transport.longCommitHash='+sha in props,props
 assert 'netty-transport.repoStatus=clean' in props,props
 assert b'shouldAutoRead' in new.read('io/netty/channel/DefaultChannelPipeline.class')
 classcounts={'official_classes':len(oc),'release_classes':len(nc),'missing_classes':sorted(oc-nc)}
with zipfile.ZipFile(release/f'netty-transport-{v}-sources.jar') as src:
 assert b'protected boolean shouldAutoRead()' in src.read('io/netty/channel/DefaultChannelPipeline.java')
 assert len([x for x in src.namelist() if x.endswith('.java')])>=196
for name in ('DefaultChannelPipeline.java','AbstractChannel.java'):
 with zipfile.ZipFile(release/f'netty-transport-{v}-sources.jar') as src:
  actual=src.read('io/netty/channel/'+name)
  expected=(Path('/code/ws/W1/workspace/netty-012118/transport/src/main/java/io/netty/channel')/name).read_bytes()
  assert actual==expected,name
assert (ev/'netty-source.txt').read_text().strip()==sha
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(release.iterdir())}
recorded=dict(line.split(maxsplit=1)[::-1] for line in (ev/'sha256.txt').read_text().splitlines())
for p in release.iterdir(): assert recorded['release/'+p.name]==hashes[p.name]
totals={'tests':0,'failures':0,'errors':0,'skipped':0};cases=[]
for p in ev.glob('TEST-*.xml'):
 root=ET.parse(p).getroot()
 if root.get('name')=='io.netty.channel.DefaultChannelPipelineAutoReadTest':
  assert root.get('tests')=='4' and all(root.get(k,'0')=='0' for k in ('failures','errors','skipped'))
  continue
 for k in totals: totals[k]+=int(root.get(k,'0'))
 assert not root.findall('.//flakyFailure') and not root.findall('.//rerunFailure')
 cases += [root.get('name')+'.'+x.get('name') for x in root.findall('testcase')]
assert totals=={'tests':25,'failures':0,'errors':0,'skipped':0},totals
assert any('DevelopYamuxPausedStreamKeepsConnectionReadingTest.closingAPausedStream' in x for x in cases),cases
summary={'source':sha,'coordinate':'io.netty:netty-transport:'+v,'sha256':hashes,'inventory':classcounts,'consumer_tests':totals,'cases':cases}
(base/'verified-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k!='cases'},indent=2))
