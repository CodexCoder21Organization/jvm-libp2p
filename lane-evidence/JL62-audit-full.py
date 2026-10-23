from pathlib import Path
from datetime import datetime
import json,xml.etree.ElementTree as ET
root=Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/JL62')
out=root.parent.parent/'out'
samples=[json.loads(x) for x in (out/'JL62-full-memory.jsonl').read_text().splitlines() if x.strip()]
submitted=datetime.fromisoformat(samples[0]['utc']).timestamp()
summary={'submitted_utc':samples[0]['utc'],'gradle_output_exists':(out/'JL62-full.log').stat().st_size>0,'fresh_xml_count':0,'stale_xml_count':0,'tests':0,'failures':0,'errors':0,'skipped':0,'failure_identities':[],'memory_before':samples[0]['memory_current'],'sampled_peak':max(x['memory_current'] for x in samples),'last_sample':samples[-1]}
for p in root.glob('**/build/test-results/test/TEST-*.xml'):
 if p.stat().st_mtime<submitted:
  summary['stale_xml_count']+=1
  continue
 summary['fresh_xml_count']+=1
 suite=ET.parse(p).getroot()
 for k in ['tests','failures','errors','skipped']:summary[k]+=int(suite.get(k,0))
 for case in suite.findall('testcase'):
  if case.find('failure') is not None or case.find('error') is not None:summary['failure_identities'].append([case.get('classname'),case.get('name')])
base={tuple(x) for x in json.loads((out/'JL62-baseline-failures.json').read_text())}
actual={tuple(x) for x in summary['failure_identities']}
summary['baseline_comparison']={'baseline_count':len(base),'observed_count':len(actual),'equal':base==actual,'extra':sorted(actual-base),'missing':sorted(base-actual)}
(out/'JL62-full-audit.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
