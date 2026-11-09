from pathlib import Path
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET

e = Path(__file__).resolve().parent
name = sys.argv[1]
rows = []
retry_tags = []
for xml in sorted((e / (name + '-xml')).glob('*.xml')):
    tree = ET.parse(xml)
    for parent in tree.iter():
        for child in list(parent):
            if child.tag in ['properties', 'system-out', 'system-err']:
                parent.remove(child)
    for case in tree.iter('testcase'):
        issue = case.find('failure')
        if issue is None:
            issue = case.find('error')
        stack = issue.text or '' if issue is not None else ''
        rows.append(dict(classname=case.get('classname'), name=case.get('name'),
                         outcome='skip' if case.find('skipped') is not None else ('fail' if issue is not None else 'pass'),
                         issue_type=issue.get('type') if issue is not None else None,
                         message=issue.get('message') if issue is not None else None,
                         test_locations=re.findall(r'\(([^()]+Test\.kt:\d+)\)', stack),
                         xml=xml.name))
    retry_tags += [node.tag for node in tree.iter() if any(word in node.tag.lower() for word in ['retry', 'rerun', 'flaky'])]
    tree.write(xml, encoding='unicode')
counts = collections.Counter(row['outcome'] for row in rows)
summary = dict(total=len(rows), counts=dict(counts), xml_files=len(list((e / (name + '-xml')).glob('*.xml'))), retry_tags=retry_tags, cases=rows)
(e / (name + '-summary.json')).write_text(json.dumps(summary, indent=2))
print('OBSERVED:', name, 'cases', len(rows), 'outcomes', dict(counts), 'XML files', summary['xml_files'], 'retry tags', len(retry_tags))
for cls in sorted(set(row['classname'] for row in rows)):
    cases = [row for row in rows if row['classname'] == cls]
    print(cls.split('.')[-1], dict(collections.Counter(row['outcome'] for row in cases)), 'failure locations', sorted(set(loc for row in cases for loc in row['test_locations']))[:8])
