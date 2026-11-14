from pathlib import Path
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

lane = Path(__file__).resolve().parents[3]
evidence = Path(__file__).resolve().parent
label = sys.argv[1] if len(sys.argv) > 1 else 'current'
root = lane / (label + '-native')
paths = subprocess.check_output(['git', 'diff', '--name-only', 'origin/develop', 'HEAD'],
                                cwd=lane / 'jvm-libp2p', text=True).splitlines()
touched = {Path(p).stem for p in paths if '/src/test/' in p and p.endswith('.kt')}
destination = evidence / ('actions-' + label + '-xml')
destination.mkdir(exist_ok=True)
cases = []
for index, source in enumerate(sorted(root.rglob('*.xml'))):
    tree = ET.parse(source)
    for case in tree.findall('.//testcase'):
        tags = [child.tag for child in case]
        retries = [tag for tag in tags if 'flaky' in tag.lower() or 'rerun' in tag.lower()]
        cases.append(dict(xml=str(source.relative_to(root)), classname=case.get('classname'),
                          name=case.get('name'), skipped='skipped' in tags,
                          failed='failure' in tags or 'error' in tags,
                          retry_tags=retries,
                          touched=case.get('classname', '').rsplit('.', 1)[-1] in touched))
    for parent in tree.iter():
        for child in list(parent):
            if child.tag in ('properties', 'system-out', 'system-err'):
                parent.remove(child)
    tree.write(destination / (str(index) + '-' + source.name), encoding='utf-8', xml_declaration=True)
selected = [case for case in cases if case['touched']]
summary = dict(xml_count=len(list(root.rglob('*.xml'))), total=len(cases),
               passed=sum(not c['failed'] and not c['skipped'] for c in cases),
               failed=sum(c['failed'] for c in cases), skipped=sum(c['skipped'] for c in cases),
               retries=[c for c in cases if c['retry_tags']],
               touched_total=len(selected),
               touched_passed=sum(not c['failed'] and not c['skipped'] for c in selected))
(evidence / ('actions-' + label + '-cases.json')).write_text(json.dumps(cases, indent=2))
(evidence / ('actions-' + label + '-summary.json')).write_text(json.dumps(summary, indent=2))
print(json.dumps(summary))
