from pathlib import Path
import xml.etree.ElementTree as ET,json,shutil,sys
repo=Path(__file__).resolve().parent.parent/'jvm-libp2p';d=Path(sys.argv[1]);d.mkdir(exist_ok=True)
findings=repo.parent.parent.parent/'out/F68g-findings.md'
for p in (repo/'libp2p/build/test-results/test').glob('TEST-*.xml'):
 shutil.copy2(p,d/p.name)
 root=ET.parse(p).getroot();r=dict(root.attrib);r['ms']=round(float(root.get('time'))*1000)
 (d/(p.stem+'.json')).write_text(json.dumps(r,indent=2))
 with findings.open('a') as f:
  f.write('\nOBSERVED: '+str(d)+' native XML first attempt '+json.dumps(r)+'\n')
  for c in root.findall('testcase'):
   for fail in list(c.findall('failure'))+list(c.findall('error')):f.write('\nFull failure '+c.get('name')+':\n```text\n'+(fail.text or '')+'\n```\n')
  f.write('\nFull system-out:\n```text\n'+(root.findtext('system-out') or '')+'\n```\n')
 print(json.dumps(r))
