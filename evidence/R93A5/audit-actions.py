from collections import Counter
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

evidence = Path(__file__).resolve().parent
lane = evidence.parents[3]
register = []
touched = set(json.loads((lane / 'prior/F93o/evidence/F93o/touched-classes.json').read_text()))
for artifact_file in sorted(evidence.glob('actions-*-artifacts.json')):
    run_id = int(artifact_file.name.split('-')[1])
    for artifact in json.loads(artifact_file.read_text())['artifacts']:
        source = lane / 'actions-download' / str(artifact['id'])
        target = evidence / 'actions-xml' / str(artifact['id'])
        counts = Counter()
        main_counts = Counter()
        cases = []
        retries = []
        files = []
        for xml in sorted(source.rglob('*.xml')):
            tree = ET.parse(xml)
            relative = xml.relative_to(source)
            files.append({'path': str(relative), 'sha256': hashlib.sha256(xml.read_bytes()).hexdigest()})
            for case in tree.findall('.//testcase'):
                failed = case.find('failure') is not None or case.find('error') is not None
                skipped = case.find('skipped') is not None
                outcome = 'failed' if failed else 'skipped' if skipped else 'passed'
                counts['total'] += 1
                counts[outcome] += 1
                if 'libp2p/build/test-results/test/' in str(relative):
                    main_counts['total'] += 1
                    main_counts[outcome] += 1
                record = {'file': str(relative), 'class': case.get('classname'), 'name': case.get('name'), 'outcome': outcome}
                cases.append(record)
                for child in case.iter():
                    if any(term in child.tag.lower() for term in ('rerun', 'flaky', 'retry')):
                        retries.append(dict(record, tag=child.tag))
            for parent in tree.iter():
                for child in list(parent):
                    if child.tag in ('properties', 'system-out', 'system-err'):
                        parent.remove(child)
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            tree.write(destination, encoding='utf-8', xml_declaration=True)
        noise = [c for c in cases if c['class'] == 'io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest']
        touched_cases = [c for c in cases if c['class'] in touched]
        result = {
            'run': run_id, 'artifact': artifact['id'], 'artifact_name': artifact['name'],
            'counts': dict(counts), 'main_suite': dict(main_counts), 'retries': retries,
            'skips': [c for c in cases if c['outcome'] == 'skipped'],
            'failures': [c for c in cases if c['outcome'] == 'failed'],
            'noise': noise, 'touched': touched_cases, 'files': files,
        }
        register.append(result)
        (evidence / ('actions-' + str(artifact['id']) + '-cases.json')).write_text(json.dumps(cases, indent=2))
(evidence / 'actions-register.json').write_text(json.dumps(register, indent=2))
for result in register:
    print(json.dumps({key: result[key] for key in ('run', 'artifact', 'counts', 'main_suite', 'retries')}))
