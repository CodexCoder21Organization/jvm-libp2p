from pathlib import Path
from shutil import copy2
import xml.etree.ElementTree as ET
import json
repo = Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/workspace/lane257/jvm-libp2p')
out = Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out')
counts = {}
for group in sorted((repo/'libp2p/build/test-results').glob('lane257*')):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    dest = out/'r257-verification-xml'/group.name
    dest.mkdir(parents=True, exist_ok=True)
    for f in group.glob('TEST-*.xml'):
        copy2(f, dest/f.name)
        root = ET.parse(f).getroot()
        for key in totals: totals[key] += int(root.get(key, '0'))
        for case in root.findall('testcase'):
            failure = case.find('failure')
            if failure is not None:
                print('FAILURE', group.name, case.get('classname'), case.get('name'), failure.get('message'))
    counts[group.name] = totals
(out/'r257-verification-counts.json').write_text(json.dumps(counts, indent=2)+'\n')
print(json.dumps(counts, indent=2))
