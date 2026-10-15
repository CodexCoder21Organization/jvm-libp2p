import collections, json, shutil, sys, xml.etree.ElementTree as ET
from pathlib import Path
repo, label = Path(sys.argv[1]), sys.argv[2]
out = Path(__file__).parent
dest = out / 'r269-xml' / label
dest.mkdir(parents=True, exist_ok=True)
counts = collections.defaultdict(lambda: {'pass': 0, 'fail': 0, 'skip': 0})
failures = []
prefix = sys.argv[4] if len(sys.argv) > 4 else 'lane269Repeat'
for xml in sorted((repo / 'libp2p/build/test-results').glob(prefix + '*/*.xml')):
    suffix = xml.parent.name.removeprefix(prefix)
    if len(sys.argv) > 3 and suffix.isdigit() and int(suffix) > int(sys.argv[3]):
        continue
    target = dest / xml.parent.name
    target.mkdir(exist_ok=True)
    shutil.copy2(xml, target / xml.name)
    for case in ET.parse(xml).getroot().iter('testcase'):
        key = case.get('classname') + ' ' + case.get('name')
        failure = case.find('failure')
        error = case.find('error')
        if failure is not None or error is not None:
            node = failure if failure is not None else error
            counts[key]['fail'] += 1
            failures.append({'xml': str(target/xml.name), 'name': key, 'message': node.get('message'), 'stacktrace': node.text})
        elif case.find('skipped') is not None:
            counts[key]['skip'] += 1
        else:
            counts[key]['pass'] += 1
result = {'label': label, 'cases': dict(counts), 'failures': failures}
(out / f'r269-{label}-result.json').write_text(json.dumps(result, indent=2))
print(json.dumps({'label':label,'cases':dict(counts),'failures':[{k:v for k,v in x.items() if k != 'stacktrace'} for x in failures]}, indent=2))
