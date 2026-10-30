from pathlib import Path
import shutil,sys,urllib.request,xml.etree.ElementTree as E,hashlib,subprocess
repo=Path(sys.argv[1]);upstream=Path(sys.argv[2])
source_sha=subprocess.check_output(['git','-C',str(upstream),'rev-parse','HEAD'],text=True).strip()
version='1.3.0-w4-local-'+source_sha[:12]
jars=[p for p in (upstream/'libp2p/build/libs').glob('*.jar') if not any(t in p.name for t in ['-sources','-javadoc','-test'])]
assert len(jars)==1,jars
bundle=repo/'.github/local-libp2p';bundle.mkdir(parents=True,exist_ok=True)
jar=bundle/f'jvm-libp2p-{version}.jar';shutil.copyfile(jars[0],jar)
pom=urllib.request.urlopen('https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-41/jvm-libp2p-1.3.0-codexcoder21-snapshot-41.pom').read()
ns={'m':'http://maven.apache.org/POM/4.0.0'};E.register_namespace('',ns['m']);root=E.fromstring(pom)
root.find('m:version',ns).text=version
E.ElementTree(root).write(bundle/f'jvm-libp2p-{version}.pom',encoding='utf-8',xml_declaration=True)
p=repo/'build.kts';s=p.read_text();needle='val dependencies = resolveDependencies2(dependencyDeclarations)';assert s.count(needle)==1
s=s.replace(needle,'''val dependencies = resolveDependencies2(dependencyDeclarations).map { artifact ->
    if (artifact.groupId == "community.kotlin.libp2p" && artifact.artifactId == "jvm-libp2p") {
        buildLocalLibp2pForProof()
    } else artifact
}

@MavenArtifactCoordinates("community.kotlin.libp2p:jvm-libp2p:1.3.0-w4-local-proof")
fun buildLocalLibp2pForProof(): File = File(".github/local-libp2p")''')
tail = 'compileDependencies = dependencies\n    )\n}'
assert s.count(tail) == 1
s = s.replace(tail, 'compileDependencies = dependencies\n    ).also { produced ->\n        // The proof declares this workspace-local module directly on every test below.\n        // Keep it out of protocol\'s POM because the resolver stages each local root separately.\n        val pom = produced.pom\n        pom.writeText(pom.readText().replace(\n            Regex("""(?s)<dependency>\\s*<groupId>community.kotlin.libp2p</groupId>\\s*<artifactId>jvm-libp2p</artifactId>.*?</dependency>"""),\n            "",\n        ))\n    }\n}')
p.write_text(s.replace('1.3.0-w4-local-proof',version))
for p in repo.glob('tests/testR104Peer*.kts'):
 s=p.read_text();old='@file:WithArtifact("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41")';assert s.count(old)==1
 new='@file:WithArtifact("foundation.url.protocol.buildLocalLibp2pForProof()")'
 staged=s.replace(old,new);assert staged.replace(new,old)==s
 p.write_text(staged)
 print('Original test SHA256',p.name,hashlib.sha256(s.encode()).hexdigest())
print('Local proof coordinate version',version)
print('Upstream source',source_sha)
print('Local library SHA256',hashlib.sha256(jar.read_bytes()).hexdigest())
print('Only proof checkout dependency wiring changed; test bodies and product branch are unchanged.')
