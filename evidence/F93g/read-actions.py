from pathlib import Path
import json
import sys
import zipfile
from xml.etree import ElementTree as E

archive, destination = sys.argv[1:]
out = Path(destination)
out.mkdir(parents=True, exist_ok=True)
counts = dict(attempts=0, passed=0, failed=0, skipped=0, retries=[])
with zipfile.ZipFile(archive) as bundle:
    number = 0
    for name in bundle.namelist():
        if not name.endswith('.xml'):
            continue
        try:
            tree = E.fromstring(bundle.read(name))
        except E.ParseError:
            continue
        for node in tree.iter():
            for child in list(node):
                if child.tag == 'properties':
                    node.remove(child)
        for case in tree.iter('testcase'):
            counts['attempts'] += 1
            if case.find('failure') is not None or case.find('error') is not None:
                counts['failed'] += 1
            elif case.find('skipped') is not None:
                counts['skipped'] += 1
            else:
                counts['passed'] += 1
            if any(any(word in node.tag.lower() for word in ('rerun', 'flaky', 'retry')) for node in case.iter()):
                counts['retries'].append([case.get('classname'), case.get('name')])
        number += 1
        E.ElementTree(tree).write(out / (str(number) + '-' + Path(name).name), encoding='unicode')
(out / 'summary.json').write_text(json.dumps(counts, indent=2))
print(json.dumps(counts))
