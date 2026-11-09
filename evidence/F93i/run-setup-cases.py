from pathlib import Path
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

e = Path(__file__).resolve().parent
lane = e.parents[2]
build = lane / 'f93i-build'
name, overlay, count = sys.argv[1:]
destination = e / name
destination.mkdir(exist_ok=True)
classpath = ':'.join(([str(build / overlay)] if overlay != 'pr' else []) + [(build / 'runtime-classpath').read_text()])
results = []
for attempt in range(1, int(count) + 1):
    started = time.monotonic()
    with (destination / ('attempt-%02d.log' % attempt)).open('w') as output:
        result = subprocess.run([str(e / 'with-slots.sh'), '1', '/opt/java/bin/java', '-Dio.netty.eventLoopThreads=2', '-Xmx128m', '-cp', classpath, 'io.libp2p.security.noise.NoiseHandshakePathHarness', 'sequential-queued'], cwd=lane / 'jvm-libp2p', stdout=output, stderr=subprocess.STDOUT, timeout=120)
    results.append(dict(attempt=attempt, exit=result.returncode, seconds=round(time.monotonic() - started, 3)))
    suite = ET.Element('testsuite', name=name, tests='1', failures=str(int(result.returncode != 0)))
    case = ET.SubElement(suite, 'testcase', classname='io.libp2p.security.noise.NoiseHandshakePathHarness', name='sequential-queued', time=str(results[-1]['seconds']))
    if result.returncode:
        ET.SubElement(case, 'failure', message='Connection setup harness exited with code ' + str(result.returncode))
    ET.ElementTree(suite).write(destination / ('attempt-%02d.xml' % attempt))
    (e / (name + '-summary.json')).write_text(json.dumps(dict(attempts=len(results), passed=sum(r['exit'] == 0 for r in results), failed=sum(r['exit'] != 0 for r in results), retries=0, results=results), indent=2))
print(json.dumps(dict(attempts=len(results), passed=sum(r['exit'] == 0 for r in results), failed=sum(r['exit'] != 0 for r in results), retries=0)))
