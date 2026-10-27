import json
from pathlib import Path
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile

checkout = Path(sys.argv[1]).resolve()
init_script = Path(sys.argv[2]).resolve()
proof = Path(sys.argv[3]).resolve()
proof.mkdir(parents=True, exist_ok=True)
started = time.monotonic()
result = ET.Element('testsuite', name='DocumentationBuildBudgetTest', tests='1', failures='0')
case = ET.SubElement(result, 'testcase', classname='DocumentationBuildBudgetTest',
                     name='allJavadocArchivesBuildWithinTheOriginalBudget')
try:
    command = ['./gradlew', '--no-daemon', '--stacktrace', '--continue', '--parallel',
               '-I', str(init_script), 'assemble', 'spotlessCheck', 'detekt', 'testClasses', 'dokkaJar']
    with (proof / 'gradle.log').open('w') as output:
        build = subprocess.run(command, cwd=checkout, stdout=output, stderr=subprocess.STDOUT, timeout=1200)
    print('Gradle exit:', build.returncode, flush=True)
    ledger = checkout / 'build/dokka-budget-proof/tasks.jsonl'
    assert ledger.exists(), 'The build did not reach documentation generation; read gradle.log for the original failure.'
    entries = [json.loads(line) for line in ledger.read_text().splitlines()]
    finished = [entry for entry in entries if entry['phase'] == 'after']
    assert finished, 'No documentation generation outcome was recorded.'
    failures = [(entry['task'], entry['failure']) for entry in finished if entry['failure']]
    assert not failures, 'Documentation generation must succeed under the original build budget: ' + repr(failures)
    modules = ['libp2p', 'interop-test-client', 'tools/schedulers', 'tools/simulator',
               'examples/chatter', 'examples/cli-chatter', 'examples/pinger']
    archives = []
    for module in modules:
        jars = list((checkout / module / 'build/libs').glob('*-javadoc.jar'))
        assert len(jars) == 1, f'{module}: expected one Javadoc archive, found {jars}'
        with zipfile.ZipFile(jars[0]) as archive:
            assert 'index.html' in archive.namelist(), f'{module}: Javadoc archive has no index.html'
        archives.append(str(jars[0].relative_to(checkout)))
    print('Documentation archive assertions passed:', archives, flush=True)
    worker_pids = set(pid for entry in finished for pid in entry['workers'])
    print('Worker pids across all generations:', sorted(worker_pids), flush=True)
    print('Root peak used Metaspace:', max(entry['rootMetaspace'] for entry in entries), flush=True)
    if 'pluginMode=V2Enabled' in (checkout / 'gradle.properties').read_text():
        assert len(worker_pids) == 1, f'Javadoc generations must reuse one owned worker, observed {worker_pids}'
        assert all(entry['rootMetaspaceMax'] == 400 * 1024 * 1024 for entry in entries), 'Gradle Metaspace budget changed'
        worker_reports = list((checkout / 'build/dokka-budget-proof').glob('*-worker-*.txt'))
        assert worker_reports, 'The documentation worker JVM budget was not recorded'
        assert all('-XX:MaxMetaspaceSize=112m' in path.read_text() and '-Xmx1G' in path.read_text()
                   for path in worker_reports), 'Documentation worker must retain its 1GiB/112MiB budget'
except BaseException:
    result.set('failures', '1')
    failure = ET.SubElement(case, 'failure', type='AssertionError')
    failure.text = traceback.format_exc()
    print(failure.text, flush=True)
finally:
    case.set('time', str(time.monotonic() - started))
    ET.ElementTree(result).write(proof / 'TEST-DocumentationBuildBudgetTest.xml', encoding='utf-8', xml_declaration=True)
if result.get('failures') != '0':
    sys.exit(1)
