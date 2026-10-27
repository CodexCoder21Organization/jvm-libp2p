from pathlib import Path
import os,hashlib,json,re
p=Path('/home/u/lanes/hd27/out');e=Path('/home/u/lanes/hd27/ws/hq023/workspace/jvm-libp2p/investigation/hq032/direct-phase-inputs.txt')
entries=[]
for line in e.read_text().splitlines()[1:]:
 path,digest=line.rsplit(' sha256=',1);path=Path(path)
 if path.name in ('unittest-bootstrap-runner-entrypoint.jar','unittest-bootstrap-runner.jar','resolver-0.0.1338.jar') or re.fullmatch(r'[0-9a-f]{64}\.jar',path.name):continue
 entries.append((path,digest))
names={x.name for x,_ in entries};index={n:[] for n in names}
roots=[Path('/home/u/lanes/hd27/ws/hq071/component/dependencies'),Path('/home/u/.cache/coursier/v1'),Path('/home/u/.aibuildcaches/_home_u_lanes_hd27_ws_hq023_workspace_UrlProtocol')]
for root in roots:
 for base,dirs,files in os.walk(root):
  for name in names.intersection(files):index[name].append(Path(base)/name)
rows=[];missing=[]
for old,digest in entries:
 candidates=[old] if old.is_file() else []
 candidates+=index[old.name]
 match=next((c for c in candidates if hashlib.sha256(c.read_bytes()).hexdigest()==digest),None)
 if match is None:missing.append({'name':old.name,'sha256':digest,'candidates':[str(c) for c in candidates]})
 else:rows.append({'path':str(match),'sha256':digest,'original':str(old)})
(p/'hq071-component-cp-audit.json').write_text(json.dumps({'resolved':rows,'missing':missing},indent=2))
print('Resolved',len(rows),'missing',missing)
if missing:raise SystemExit(1)
(p/'hq071-component-cp.txt').write_text(os.pathsep.join(r['path'] for r in rows))
