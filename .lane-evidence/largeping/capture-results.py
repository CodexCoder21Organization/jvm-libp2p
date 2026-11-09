import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

stage = sys.argv[1]
target = Path('.lane-evidence/largeping') / stage
target.mkdir(exist_ok=True)
passed = total = 0
lines = []
for source in sorted(Path('libp2p/build/test-results/test').glob('TEST-*.xml')):
    root = ET.parse(source).getroot()
    count = int(root.get('tests', 0))
    failed = int(root.get('failures', 0)) + int(root.get('errors', 0))
    skipped = int(root.get('skipped', 0))
    total += count
    passed += count - failed - skipped
    shutil.copy2(source, target / source.name)
    line = f"{root.get('name')}: FIRST {count-failed-skipped}/{count}; skipped {skipped}"
    lines.append(line)
    print(line)
lines.append(f'TOTAL FIRST {passed}/{total}')
print(lines[-1])
(target / 'counts.txt').write_text('\n'.join(lines) + '\n')
