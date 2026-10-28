import json
import re
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
html_mode = len(sys.argv) > 4 and sys.argv[4] in ('html', 'html-reversal')
started = time.monotonic()
result = ET.Element('testsuite', name='DocumentationBuildBudgetTest', tests='1', failures='0')
case = ET.SubElement(result, 'testcase', classname='DocumentationBuildBudgetTest',
                     name=('allHtmlModulesBuildWithinTheOriginalBudget' if html_mode else 'allJavadocArchivesBuildWithinTheOriginalBudget'))
try:
    command = ['./gradlew', '--no-daemon', '--stacktrace', '--continue', '--parallel',
               '-I', str(init_script)]
    if len(sys.argv) > 4 and sys.argv[4] == 'retention-red':
        command += ['-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:+ExitOnOutOfMemoryError']
    command += ['dokkaHtml'] if html_mode else ['assemble', 'spotlessCheck', 'detekt', 'testClasses', 'dokkaJar']
    with (proof / 'gradle.log').open('w') as output:
        build = subprocess.run(command, cwd=checkout, stdout=output, stderr=subprocess.STDOUT, timeout=1200)
    print('Gradle exit:', build.returncode, flush=True)
    log = (proof / 'gradle.log').read_text()
    assert 'java.lang.OutOfMemoryError' not in log, 'The complete build must finish without an OutOfMemoryError, including Gradle shutdown:\n' + log
    assert 'Could not stop all services' not in log, 'Gradle service shutdown failed:\n' + log
    assert 'Module was compiled with an incompatible version of Kotlin' not in log, 'The build introduced an incompatible Kotlin metadata version:\n' + log
    if not html_mode and build.returncode != 0:
        failed_tasks = re.findall(r'^> Task (\S+) FAILED$', log, re.MULTILINE)
        assert build.returncode == 1 and failed_tasks == [':libp2p:detekt'], 'Unexpected build failure outside the known develop Detekt findings:\n' + log
        expected = [
            'MuxChannel.kt:401:14: The caught exception is swallowed. The original exception could be lost. [SwallowedException]',
            'NestedConnectionCloseNotificationTest.kt:103:38: Do not throw an exception within a finally statement. This can discard exceptions and is confusing. [ThrowingExceptionFromFinally]',
            'NoiseMatcherReadinessTest.kt:72:47: Do not throw an exception within a finally statement. This can discard exceptions and is confusing. [ThrowingExceptionFromFinally]'
        ]
        assert all(message in log for message in expected), 'The Detekt outcome differs from the recorded develop baseline:\n' + log
        assert log.count('FAILURE: Build failed with an exception.') == 1, 'The build reports an additional failure:\n' + log
        assert '> Analysis failed with 3 weighted issues.' in log, 'The Detekt issue count changed:\n' + log
    if html_mode:
        assert build.returncode == 0, 'The legacy dokkaHtml command must generate HTML successfully. Gradle failed:\n' + (proof / 'gradle.log').read_text()
    ledger = checkout / 'build/dokka-budget-proof/tasks.jsonl'
    assert ledger.exists(), 'The build did not reach documentation generation; read gradle.log for the original failure.'
    entries = [json.loads(line) for line in ledger.read_text().splitlines()]
    finished = [entry for entry in entries if entry['phase'] == 'after']
    assert finished, 'No documentation generation outcome was recorded.'
    failures = [(entry['task'], entry['failure']) for entry in finished if entry['failure']]
    assert not failures, 'Documentation generation must succeed under the original build budget: ' + repr(failures)
    modules = ['libp2p', 'interop-test-client', 'tools/schedulers', 'tools/simulator',
               'examples/chatter', 'examples/cli-chatter', 'examples/pinger']
    outputs = []
    for module in modules:
        if html_mode:
            index = checkout / module / 'build/dokka/index.html'
            assert index.exists(), f'{module}: HTML documentation has no index.html'
            outputs.append(str(index.relative_to(checkout)))
        else:
            jars = list((checkout / module / 'build/libs').glob('*-javadoc.jar'))
            assert len(jars) == 1, f'{module}: expected one Javadoc archive, found {jars}'
            with zipfile.ZipFile(jars[0]) as archive:
                assert 'index.html' in archive.namelist(), f'{module}: Javadoc archive has no index.html'
            outputs.append(str(jars[0].relative_to(checkout)))
    print('Documentation output assertions passed:', outputs, flush=True)
    worker_pids = set(pid for entry in finished for pid in entry['workers'])
    print('Worker pids across all generations:', sorted(worker_pids), flush=True)
    print('Root peak used Metaspace:', max(entry['rootMetaspace'] for entry in entries), flush=True)
    assert not worker_pids, f'Documentation must remain in the original capped Gradle process, saw workers {worker_pids}'
    assert all(entry['rootMetaspaceMax'] == 512 * 1024 * 1024 for entry in entries), 'Original aggregate512MiB cap changed'
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
