import hashlib, json, pathlib, subprocess, sys, xml.etree.ElementTree as ET, zipfile
S = pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad')
repo = S / 'workspace/PUBLIBP2P2'
out = S / 'out'
stage = out / 'PUBLIBP2P2-stage'
V = '1.3.0-codexcoder21-snapshot-39'
ns = {'m':'http://maven.apache.org/POM/4.0.0'}
pom = stage / f'jvm-libp2p-{V}.pom'
jar = stage / f'jvm-libp2p-{V}.jar'
root = ET.parse(pom).getroot()
assert [root.findtext(f'm:{x}', namespaces=ns) for x in ['groupId','artifactId','version']] == ['community.kotlin.libp2p','jvm-libp2p',V]
def deps(path):
    tree=ET.parse(path).getroot()
    return [ET.tostring(e,encoding='unicode') for e in tree.findall('m:dependencies/m:dependency',ns)]
assert deps(pom) == deps(out/'PUBLIBP2P2-prior38.pom'), 'Published dependency declarations differ from prior release'
classes = 0
with zipfile.ZipFile(jar) as z:
    assert not any(n.startswith('io/netty/') for n in z.namelist()), 'Dependency classes bundled in library jar'
    for name in z.namelist():
        if name.endswith('.class'):
            candidates = [repo/'libp2p/build/classes'/language/'main'/name for language in ['kotlin','java']]
            compiled = next((p for p in candidates if p.is_file()),None)
            assert compiled, name
            assert z.read(name)==compiled.read_bytes(), name
            classes += 1
results = {}
for cls in ['io.libp2p.mux.yamux.YamuxHandler','io.libp2p.core.Connection','io.libp2p.transport.implementation.ConnectionOverNetty','io.libp2p.transport.implementation.NettyChannelCloseKt','io.libp2p.mux.yamux.YamuxCapacityStreamMuxer']:
    code = subprocess.check_output(['javap','-p','-c','-classpath',str(jar),cls],text=True)
    (out/('PUBLIBP2P2-local-'+cls.rsplit('.',1)[-1]+'-javap.txt')).write_text(code)
    results[cls]=code
assert 'coalescePendingChildRead(io.netty.buffer.ByteBuf, io.netty.buffer.ByteBuf)' in results['io.libp2p.mux.yamux.YamuxHandler']
assert 'retryClose()' in results['io.libp2p.core.Connection']
assert 'retryNettyChannelClose' in results['io.libp2p.transport.implementation.ConnectionOverNetty']
assert 'retryNettyChannelClose' in results['io.libp2p.transport.implementation.NettyChannelCloseKt']
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip() == 'bd7edb2570fb609c20245305c25af29d166a67c6'
assert subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True) == ''
files = {p.name:dict(bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [jar,pom]}
report=dict(coordinate='community.kotlin.libp2p:jvm-libp2p:'+V,head='bd7edb2570fb609c20245305c25af29d166a67c6',tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=repo,text=True).strip(),classes_matched_to_local_compiler=classes,dependencies=len(deps(pom)),files=files)
(out/'PUBLIBP2P2-local-verified.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
