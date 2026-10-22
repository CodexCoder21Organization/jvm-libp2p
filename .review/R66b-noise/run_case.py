from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[2]
selector = 'io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.initiator times out silent responder while waiting for message 2'
label = sys.argv[1]
number = int(sys.argv[2])
run_dir = repo / '.review' / 'R66b-noise' / label
run_dir.mkdir(parents=True, exist_ok=True)
xml_dir = repo / 'libp2p' / 'build' / 'test-results' / 'test'
shutil.rmtree(xml_dir, ignore_errors=True)
command = ['timeout', '--signal=TERM', '180s', './gradlew', '--no-daemon', ':libp2p:test', '--tests', selector, '--rerun']
command_text = "timeout --signal=TERM 180s ./gradlew --no-daemon :libp2p:test --tests '" + selector + "' --rerun"
started = time.monotonic()
result = subprocess.run(command, cwd=repo, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
elapsed = time.monotonic() - started
xmls = sorted(xml_dir.glob('*.xml'))
valid = len(xmls) == 1
first = 'invalid'
case_count = 0
if valid:
    shutil.copy2(xmls[0], run_dir / f'run-{number:02d}.xml')
    root = ET.parse(xmls[0]).getroot()
    cases = [node for node in root.iter() if node.tag.rsplit('}', 1)[-1] == 'testcase']
    case_count = len(cases)
    valid = case_count == 1
    if valid:
        case = cases[0]
        expected_class = 'io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest'
        expected_name = 'initiator times out silent responder while waiting for message 2'
        valid = case.attrib.get('classname') == expected_class and case.attrib.get('name') in {expected_name, expected_name + '()'}
        if valid:
            tags = [node.tag.rsplit('}', 1)[-1].lower() for node in case.iter()]
            failed_tags = {'failure', 'error', 'flakyfailure', 'flakyerror', 'rerunfailure', 'rerunerror'}
            retry_tags = sorted(tag for tag in tags if tag in {'flakyfailure', 'flakyerror', 'rerunfailure', 'rerunerror'})
            if any(tag in failed_tags for tag in tags):
                first = 'fail'
            elif 'skipped' in tags:
                first = 'skip'
            else:
                first = 'pass'
meta = run_dir / f'run-{number:02d}.meta'
meta.write_text(
    f'command={command_text}\n'
    f'exit_status={result.returncode}\n'
    f'elapsed_seconds={elapsed:.3f}\n'
    f'xml_count={len(xmls)}\n'
    f'case_count={case_count}\n'
    f'retry_tag_count={len(retry_tags) if valid else 0}\n'
    f'first_attempt={first}\n'
)
print(f'{label} run-{number:02d} exit={result.returncode} cases={case_count} first={first} seconds={elapsed:.3f}')
if not valid:
    sys.exit(2)
