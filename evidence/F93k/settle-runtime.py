from pathlib import Path
import json
import os
import select
import subprocess
import sys

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
product = lane / 'jvm-libp2p'
states = []
resume_gap = sys.argv[2] if len(sys.argv) > 2 else None
if resume_gap:
    states = json.loads((evidence / 'runtime-pipeline-state.json').read_text())['steps']

def save(state):
    (evidence / 'runtime-pipeline-state.json').write_text(json.dumps(dict(state=state, steps=states), indent=2))

def command(arguments):
    return subprocess.run(arguments, cwd=lane, check=False).returncode

def apply(name):
    if subprocess.run(['git', 'apply', '--check', str(evidence / name)], cwd=product,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        subprocess.run(['git', 'apply', '--reverse', '--check', str(evidence / name)], cwd=product, check=True)
        return
    subprocess.run(['git', 'apply', str(evidence / name)], cwd=product, check=True)

def run(label, overlay, name, expected_passes):
    code = command([sys.executable, str(evidence / 'run-console.py'), label, overlay, name])
    summary = json.loads((evidence / (label + '-summary.json')).read_text())
    states.append(dict(label=label, result=summary))
    save('RUNNING')
    valid = summary['total'] == 1 and summary['passed'] == expected_passes and not summary['skipped'] and not summary['retries']
    valid = valid and code == (0 if expected_passes else 1)
    if not valid:
        save('STOP at ' + label)
        raise RuntimeError('PROBLEM: unexpected result at ' + label + '; inspect its saved XML before proceeding')

pid = int(sys.argv[1])
try:
    descriptor = os.pidfd_open(pid) if pid > 0 else None
except ProcessLookupError:
    descriptor = None
if descriptor is not None:
    try:
        ready, _, _ = select.select([descriptor], [], [], 1800)
        if not ready:
            raise RuntimeError('PROBLEM: callback proof process exceeded continuation deadline')
    finally:
        os.close(descriptor)
callback = json.loads((evidence / 'callback-proof-state.json').read_text())
if callback['state'] != 'PROVEN':
    save('STOP at callback proof')
    sys.exit(1)
save('compiling final test variants')
sources = ['libp2p/src/test/kotlin/io/libp2p/transport/' + name + '.kt' for name in
           ['F93jQuicReleaseReentryTest', 'F93jQuicFailedDialResourceTest',
            'F93jQuicSameLoopReleaseTest', 'R93ARejectedOpenParentDialTest']]
code = 0 if resume_gap else command([sys.executable, str(evidence / 'compile-overlay.py'),
                                    'final-test-classes', 'corrected-tests'] + sources)
if code:
    save('STOP at final test compilation')
    sys.exit(code)

items = [
    ('g2', 'F93jQuicReleaseReentryTest', 'g2-red-classes', 'g2-final-test-correction.patch', True),
    ('g5', 'R93ARejectedOpenParentDialTest', 'g5-red-classes', 'g5-format.patch', False),
    ('g1', 'F93jQuicSameLoopReleaseTest', 'g1-red-classes', 'g1-format.patch', False),
    ('g7', 'F93jQuicFailedDialResourceTest', 'g7-red-classes', 'g7-final-test-correction.patch', True)
]
if resume_gap:
    items = items[next(index for index, item in enumerate(items) if item[0] == resume_gap):]
for gap, name, control, patch, original in items:
    if original:
        code = command([sys.executable, str(evidence / 'run-console.py'), gap + '-original-control', control, name])
        summary = json.loads((evidence / (gap + '-original-control-summary.json')).read_text())
        states.append(dict(label=gap + '-original-control', result=summary))
        if summary['total'] != 1 or summary['skipped'] or summary['retries'] or code not in (0, 1):
            save('STOP at original ' + gap)
            sys.exit(1)
    apply(patch)
    reuse_completed_red = len(sys.argv) > 3 and sys.argv[3] == 'reuse-completed-g5-red' and gap == 'g5'
    if reuse_completed_red:
        observed = json.loads((evidence / 'g5-red-summary.json').read_text())
        assert observed['total'] == 1 and observed['passed'] == 0 and observed['exit'] == 143
        assert observed['cases'][0]['failure'] == 'Unexpected exception type thrown, expected: <java.util.concurrent.ExecutionException> but was: <java.util.concurrent.TimeoutException>'
        assert observed['test_sources'][name] == '8fc9728d73e368c7d56cf26a16214ea90300ab08467ee8afaa5e05f65080d28f'
    else:
        run(gap + '-red', control + ':final-test-classes', name, 0)
    run(gap + '-green', 'final-test-classes', name, 1)
    run(gap + '-revert-red', control + ':final-test-classes', name, 0)
    (evidence / (gap + '-ready-for-review.json')).write_text(json.dumps(dict(state='red/green/red complete; root must review failure locations before adoption'), indent=2))
    save('awaiting root review of ' + gap)
save('all runtime sequences complete; root review and individual pushes required')
