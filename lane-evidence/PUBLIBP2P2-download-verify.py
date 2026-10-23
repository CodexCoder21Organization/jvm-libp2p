import datetime,hashlib,json,pathlib,subprocess,urllib.request
S=pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad')
out=S/'out'
report=json.loads((out/'PUBLIBP2P2-local-verified.json').read_text())
report['verified_at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
V='1.3.0-codexcoder21-snapshot-39'
base='https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/'+V+'/'
destination=out/'PUBLIBP2P2-downloaded'
destination.mkdir(exist_ok=True)
for name,info in report['files'].items():
    with urllib.request.urlopen(base+name,timeout=30) as response:
        assert response.status==200
        data=response.read()
    (destination/name).write_bytes(data)
    sha=hashlib.sha256(data).hexdigest()
    assert sha==info['sha256'],name+' remote/local SHA-256 mismatch'
    assert len(data)==info['bytes'],name+' remote/local length mismatch'
    info.update(url=base+name,http_status=200,downloaded_sha256=sha)
jar=destination/f'jvm-libp2p-{V}.jar'
classes=['io.libp2p.mux.yamux.YamuxHandler','io.libp2p.core.Connection','io.libp2p.transport.implementation.ConnectionOverNetty','io.libp2p.transport.implementation.NettyChannelCloseKt','io.libp2p.mux.yamux.YamuxCapacityStreamMuxer']
for cls in classes:
    code=subprocess.check_output(['javap','-p','-c','-classpath',str(jar),cls],text=True)
    suffix=cls.rsplit('.',1)[-1]+'-javap.txt'
    (out/('PUBLIBP2P2-published-'+suffix)).write_text(code)
    assert code==(out/('PUBLIBP2P2-local-'+suffix)).read_text(),cls+' remote/local bytecode mismatch'
report['downloaded_javap_matches_local']=classes
repo=S/'workspace/PUBLIBP2P2'
report['source_head_after_publication']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
report['source_tree_after_publication']=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=repo,text=True).strip()
report['source_status_after_publication']=subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True)
assert report['source_head_after_publication']==report['head']
assert report['source_tree_after_publication']==report['tree']
assert report['source_status_after_publication']==''
(out/'PUBLIBP2P2-published-verified.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
