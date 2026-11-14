from pathlib import Path
import hashlib
import json
import zipfile
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
raw = evidence.parents[2] / 'actions-raw'
summaries = []
for archive in sorted(raw.glob('*.zip')):
    cases = []
    output = evidence / ('actions-' + archive.stem)
    output.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for name in sorted(z.namelist()):
            if not name.endswith('.xml'):
                continue
            try:
                root = ET.fromstring(z.read(name))
            except ET.ParseError:
                continue
            if root.tag not in ('testsuite', 'testsuites'):
                continue
            for case in root.findall('.//testcase'):
                failures = case.findall('failure') + case.findall('error')
                retries = [c.tag for c in case if 'rerun' in c.tag.lower() or 'flaky' in c.tag.lower()]
                cases.append({'file': name, 'class': case.get('classname'), 'name': case.get('name'),
                              'passed': not failures and case.find('skipped') is None,
                              'skipped': case.find('skipped') is not None, 'retries': retries,
                              'failures': [f.get('message') for f in failures]})
            for node in root.iter():
                for child in list(node):
                    if child.tag in ('properties', 'system-out', 'system-err'):
                        node.remove(child)
            ET.ElementTree(root).write(output / name.replace('/', '__'), encoding='utf-8', xml_declaration=True)
    summary = {'archive': archive.name, 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
               'passed': sum(c['passed'] for c in cases), 'total': len(cases),
               'skipped': [c for c in cases if c['skipped']], 'retries': [c for c in cases if c['retries']],
               'failed': [c for c in cases if not c['passed'] and not c['skipped']], 'cases': cases}
    (evidence / ('actions-' + archive.stem + '.json')).write_text(json.dumps(summary, indent=2))
    summaries.append({k:v for k,v in summary.items() if k != 'cases'})
(evidence / 'actions-summary.json').write_text(json.dumps(summaries, indent=2))
for summary in summaries:
    print({k:v for k,v in summary.items() if k not in ('failed', 'skipped')})
