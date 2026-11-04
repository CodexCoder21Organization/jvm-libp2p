#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source_repo="$here/../../../jvm-libp2p"
test "$(git -C "$source_repo" rev-parse HEAD)" = e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe
python3 - <<'PY'
from pathlib import Path
import hashlib,urllib.request,urllib.error,json,datetime
stage=Path('/tmp/flibp8-publish45'); version='1.3.0-codexcoder21-snapshot-45'
expected={'jar':'a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c','pom':'012b91b72db7f5eb459407f768a94f8c513730c7859ab44bead04a4abad18abf'}
checks=[]
for ext,sha in expected.items():
 p=stage/('jvm-libp2p-'+version+'.'+ext);assert hashlib.sha256(p.read_bytes()).hexdigest()==sha
 url='https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/'+version+'/'+p.name
 try:
  with urllib.request.urlopen(url,timeout=30) as r:status=r.status
 except urllib.error.HTTPError as e:status=e.code
 assert status==404,(url,status)
 checks.append({'url':url,'status':status,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()})
Path(__import__('os').environ['FLIBP8_PUBLISH_EVIDENCE'],'version-preupload.json').write_text(json.dumps(checks,indent=2)+'\n')
PY
exec "$here/with-slots.sh" 1 timeout 1200 coursier launch community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.6 -r https://kotlin.directory -- --artifact-dir /tmp/flibp8-publish45 --api-url https://api.kotlin.directory/upload --force
