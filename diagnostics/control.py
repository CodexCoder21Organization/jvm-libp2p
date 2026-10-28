from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

candidate = Path('candidate')
mode = sys.argv[1]
if mode in ('native-red', 'original_memory', 'retained_runtime'):
    command = ['python3', 'diagnostics/native-verify.py', str(candidate), 'diagnostics/native-budget.gradle', 'native-proof']
    if mode == 'retained_runtime':
        command.append('retention-red')
    result = subprocess.run(command)
    suite = ET.parse('native-proof/TEST-DocumentationBuildBudgetTest.xml').getroot()
    assert result.returncode == 1 and suite.get('failures') == '1'
    assert 'java.lang.OutOfMemoryError: Metaspace' in Path('native-proof/gradle.log').read_text()
else:
    result = subprocess.run(['python3', 'scripts/verify-documentation.py'], cwd=candidate)
    reports = candidate / 'build/documentation-verification'
    suite = ET.parse(reports / 'TEST-DocumentationContractTest.xml').getroot()
    log = (reports / 'gradle.log').read_text()
    cases = {case.get('name'): case for case in suite.findall('testcase')}
    assert result.returncode == 1
    if mode in ('budget', 'separate_runtimes'):
        failure = cases['aggregateRootAndDokkaWorkerMetaspaceBudget'].find('failure')
        expected = '855638016' if mode == 'budget' else '687865856'
        assert failure is not None and expected in failure.text, failure
    elif mode in ('html_alias', 'javadoc_alias'):
        task = 'dokkaGenerateHtml' if mode == 'html_alias' else 'dokkaGenerateJavadoc'
        assert cases['combinedBuildAndShutdown'].find('failure') is not None
        assert "Task with path '" + task + "' not found" in log
    elif mode == 'archive':
        failure = cases['javadocArchivesContainPublicApiPages'].find('failure')
        assert failure is not None and 'has no index.html' in failure.text
    elif mode == 'metadata':
        failure = cases['runtimeClasspathPinsKotlinStdlib'].find('failure')
        assert failure is not None and '1.9.25' in failure.text
        assert 'java.lang.OutOfMemoryError' not in log
    else:
        raise AssertionError(mode)
print('Expected assertion red confirmed:', mode)
