import pathlib,subprocess,hashlib,json,gzip,re
root=pathlib.Path(__file__).resolve().parents[1]; repo=root/'consumer'; ev=root/'evidence'
files=subprocess.check_output(['git','ls-files','-z'],cwd=repo).decode().split('\0');files=[f for f in files if f]
# Read-only inventory while the publish gate runs. Callers and child-JVM launches are included.
pattern=re.compile(r'\b(?:dial|connect|newStream|requestBytecodeConditionallyFromPeer|requestBytecodeFromPeer|requestRpcStream|sendServiceRpcRequest|openConnection|createHost|createServerHost)\s*\(|Libp2pNetworkProvider\s*\(|ProcessBuilder\s*\(')
selected=[]
for f in files:
 if not f.endswith('.kts') or not (f.startswith('tests/') or f in ['integration-test.kts','simulation-test.kts']):continue
 lines=(repo/f).read_text().splitlines(); matches=[[i,l.strip()] for i,l in enumerate(lines,1) if pattern.search(l)]
 if matches:selected.append({'file':f,'selector':f,'matches':matches})
(ev/'dial-content-inventory.json.gz').write_bytes(gzip.compress(json.dumps(selected,indent=2).encode()))
print('Content-selected test files:',len(selected))
# Preserve the exact historical diagnostic source bytes; they will only receive the pin replacement.
for f in sorted((ev/'inherited-dial6').glob('*.kts')):
 (ev/'diagnostic-sources').mkdir(exist_ok=True)
 (ev/'diagnostic-sources'/f.name).write_bytes(f.read_bytes())
print('Diagnostics:',len(list((ev/'diagnostic-sources').glob('*.kts'))))
