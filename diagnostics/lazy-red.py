from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
result = subprocess.run(['python3', 'scripts/verify-documentation-laziness.py'], cwd='candidate')
report = Path('candidate/build/documentation-verification/laziness')
suite = ET.parse(report / 'TEST-DocumentationResolutionTest.xml').getroot()
assert result.returncode == 1 and suite.get('failures') == '1'
assert 'A non-documentation command attempted to resolve Dokka configuration' in (report / 'gradle.log').read_text()
print('Expected ordinary-command assertion red confirmed')
