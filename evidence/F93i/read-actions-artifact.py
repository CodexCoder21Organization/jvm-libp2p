from pathlib import Path
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

archive = Path(sys.argv[1])
destination = Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True)
for previous in destination.glob('[0-9][0-9][0-9]-TEST-*.xml'):
    previous.unlink()
summary = dict(xml_files=0, first_attempts=0, passed=0, failed=0, skipped=0, retries=[], failures=[], archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
rows = []
with zipfile.ZipFile(archive) as source:
    names = [n for n in source.namelist() if Path(n).name.startswith('TEST-') and n.endswith('.xml')]
    for index, name in enumerate(names, 1):
        root = ET.fromstring(source.read(name))
        summary['xml_files'] += 1
        for case in root.iter('testcase'):
            summary['first_attempts'] += 1
            identity = dict(classname=case.get('classname'), name=case.get('name'), xml=name)
            retry_nodes = [n for n in case.iter() if any(k in n.tag.lower() for k in ['flaky', 'rerun', 'retry'])]
            failure = case.find('failure')
            if failure is None:
                failure = case.find('error')
            if retry_nodes:
                summary['retries'].append(dict(**identity, elements=[n.tag for n in retry_nodes]))
            if case.find('skipped') is not None:
                outcome = 'skipped'
            elif failure is not None or retry_nodes:
                outcome = 'failed'
                issue = failure if failure is not None else retry_nodes[0]
                summary['failures'].append(dict(**identity, locations=re.findall(r'[A-Za-z0-9_]+\.kt:\d+', issue.text or ''), message=issue.get('message')))
            else:
                outcome = 'passed'
            summary[outcome] += 1
            rows.append(dict(**identity, first_attempt=outcome, retries=len(retry_nodes)))
        for node in root.iter():
            for child in list(node):
                if child.tag == 'properties':
                    node.remove(child)
        ET.ElementTree(root).write(destination / ('%03d-' % index + Path(name).name), encoding='unicode')
if not summary['first_attempts']:
    raise RuntimeError('The Actions artifact contains no native JUnit test attempts: ' + str(archive))
(destination / 'summary.json').write_text(json.dumps(summary, indent=2))
(destination / 'attempts.json').write_text(json.dumps(rows, indent=2))
print(json.dumps({k: v for k, v in summary.items() if k not in ['failures', 'retries']}))
print('Retry cases:', len(summary['retries']), 'Failed case names:', [r['classname'] + '#' + r['name'] for r in summary['failures']])
