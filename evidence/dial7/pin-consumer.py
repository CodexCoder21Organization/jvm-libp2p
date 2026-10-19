import pathlib,subprocess,json,gzip,hashlib
root=pathlib.Path(__file__).resolve().parents[1];repo=root/'consumer';ev=root/'evidence'
version='1.3.0-codexcoder21-snapshot-33'; old='1.3.0-codexcoder21-snapshot-25'
files=subprocess.check_output(['git','grep','-l','-z','snapshot-25'],cwd=repo).decode().strip('\0').split('\0')
ledger=[]
for file in files:
 p=repo/file
 # Investigation records describe past runs and are preserved as historical evidence.
 if not file.endswith('.kts'):continue
 before=p.read_text();after=before.replace(old,version)
 # Comments explain the current pin and its carried older behavior.
 if file=='build.kts':
  after=after.replace('// snapshot-25 carries:', '// The fork carries:').replace('// snapshot-25 makes child-channel','// The fork makes child-channel')
  needle='    MavenPrebuilt2("community.kotlin.libp2p:jvm-libp2p:'+version+'"),'
  after=after.replace(needle,'    // This pin includes loop-local Noise scheduling from\n    // https://github.com/CodexCoder21Organization/jvm-libp2p/pull/53.\n'+needle)
 if before==after:continue
 p.write_text(after)
 if file!='build.kts':assert after.replace(version,old)==before,'Unexpected test edit '+file
 ledger.append({'file':file,'before':old,'after':version,'beforeSha256':hashlib.sha256(before.encode()).hexdigest(),'afterSha256':hashlib.sha256(after.encode()).hexdigest(),'justification':'Align the fork dependency with the production pin. Test body, assertions, iteration counts and bounds are byte-identical.' if file.startswith('tests/') else 'Update the production fork pin and its explanatory comments.'})
(ev/'pin-edit-ledger.json.gz').write_bytes(gzip.compress(json.dumps(ledger,indent=2).encode()));print('PIN_UPDATED',len(ledger),'files; tests',sum(r['file'].startswith('tests/') for r in ledger))
for p in (ev/'diagnostic-sources').glob('*.kts'):
 target=repo/'tests'/p.name;assert not target.exists();target.write_text(p.read_text().replace(old,version))
print('DIAGNOSTICS_ADDED',len(list((ev/'diagnostic-sources').glob('*.kts'))),'untracked; excluded from pin PR')
