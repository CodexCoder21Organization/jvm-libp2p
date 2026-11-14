from pathlib import Path
import json
import os
import select
import subprocess
import sys

evidence = Path(__file__).resolve().parent
pid = int(sys.argv[1])
try:
    descriptor = os.pidfd_open(pid)
except ProcessLookupError:
    descriptor = None
if descriptor is not None:
    try:
        ready, _, _ = select.select([descriptor], [], [], 1800)
        if not ready:
            raise RuntimeError('PROBLEM: callback green process did not exit within the continuation deadline')
    finally:
        os.close(descriptor)
green = json.loads((evidence / 'callback-green-summary.json').read_text())
if green['total'] != 2 or green['passed'] != 2 or green['exit'] != 0 or green['retries']:
    (evidence / 'callback-proof-state.json').write_text(json.dumps(dict(state='STOP', green=green), indent=2))
    print('OBSERVED callback continuation stopped: green did not pass2/2')
    sys.exit(1)
subprocess.run([sys.executable, str(evidence / 'run-console.py'), 'callback-revert-red',
                'callback-red-classes', 'F93jCallbackFailedParentReleaseTest',
                'F93jCallbackPendingChildReleaseTest'], check=False)
red = json.loads((evidence / 'callback-revert-red-summary.json').read_text())
messages = {case['classname']: case['failure'] for case in red['cases']}
expected = {
    'io.libp2p.transport.F93jCallbackFailedParentReleaseTest':
        'A callback-closed dial must wait until its UDP parent has deregistered ==> expected: <false> but was: <true>',
    'io.libp2p.transport.F93jCallbackPendingChildReleaseTest':
        'A callback close request must hold dial completion even before physical child closure ==> expected: <false> but was: <true>'
}
complete = red['total'] == 2 and red['passed'] == 0 and red['exit'] == 1 and not red['retries'] and messages == expected
(evidence / 'callback-proof-state.json').write_text(json.dumps(dict(state='PROVEN' if complete else 'STOP', green=green, red=red), indent=2))
print('OBSERVED callback proof=' + ('PROVEN' if complete else 'STOP'))
sys.exit(0 if complete else 1)
