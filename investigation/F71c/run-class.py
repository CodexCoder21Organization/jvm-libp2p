import subprocess,time,pathlib,json,sys,shutil,xml.etree.ElementTree as ET
root=pathlib.Path.cwd(); evidence=root.parent.parent.parent/'out'/'F71c-evidence'; label,cls=sys.argv[1:3]
subprocess.run(['git','fetch','origin'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['git','rebase','--autostash','origin/develop'],check=True)
cmd=['timeout','--foreground','--signal=TERM','--kill-after=30s','25m','./gradlew','--gradle-user-home','/tmp/F71c-gradle-home','--offline','--max-workers=2',':libp2p:test','--tests',cls,'--stacktrace']
start=time.monotonic(); head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
patch=subprocess.check_output(['git','diff','HEAD'],text=True);(evidence/(label+'-source.patch')).write_text(patch)
xml=root/'libp2p/build/test-results/test'/('TEST-'+cls+'.xml'); xml.unlink(missing_ok=True)
with (evidence/(label+'.log')).open('w') as log:
 p=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
record={'label':label,'class':cls,'head':head,'exit':p.returncode,'wall_ms':round((time.monotonic()-start)*1000),'command':cmd}
if xml.exists():
 shutil.copy(xml,evidence/(label+'.xml'));tree=ET.parse(xml);record['xml']=tree.getroot().attrib
 failures=[''.join(f.itertext()) for f in tree.findall('.//failure')];(evidence/(label+'-failures.txt')).write_text('\n\n'.join(failures))
 print('\n\n'.join(failures))
(evidence/(label+'.json')).write_text(json.dumps(record,indent=2)); print(json.dumps(record))
with (evidence.parent/'F71c-findings.md').open('a') as f:f.write('\nOBSERVED: '+json.dumps(record)+'\n')
sys.exit(p.returncode)
