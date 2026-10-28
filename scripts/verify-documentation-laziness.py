#!/usr/bin/env python3
"""Check that ordinary Gradle commands do not resolve documentation engines.

Run after a normal build has populated Gradle's plugin cache.
"""
import subprocess
import tempfile
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

checkout = Path(__file__).resolve().parents[1]
reports = checkout / 'build/documentation-verification/laziness'
reports.mkdir(parents=True, exist_ok=True)
suite = ET.Element('testsuite', name='DocumentationResolutionTest', tests='1', failures='0')
case = ET.SubElement(suite, 'testcase', classname='DocumentationResolutionTest',
                     name='helpDoesNotResolveDocumentationEngines')
try:
    with tempfile.TemporaryDirectory(prefix='documentation-resolution-') as temporary:
        init = Path(temporary) / 'no-documentation-resolution.gradle'
        init.write_text('''gradle.beforeProject { project ->
    project.configurations.configureEach { configuration ->
        if (configuration.name.startsWith('dokka')) {
            configuration.incoming.beforeResolve {
                throw new GradleException("A non-documentation command attempted to resolve Dokka configuration ${project.path}:${configuration.name}")
            }
        }
    }
}
''')
        with (reports / 'gradle.log').open('w') as output:
            result = subprocess.run(['./gradlew', '--no-daemon', '--offline', '--stacktrace',
                                     '-I', str(init), 'help'], cwd=checkout,
                                    stdout=output, stderr=subprocess.STDOUT, timeout=180)
        assert result.returncode == 0, ('Gradle help must succeed without resolving documentation engines:\n' +
                                       (reports / 'gradle.log').read_text())
except BaseException:
    suite.set('failures', '1')
    failure = ET.SubElement(case, 'failure', type='AssertionError')
    failure.text = traceback.format_exc()
    print(failure.text)
finally:
    ET.ElementTree(suite).write(reports / 'TEST-DocumentationResolutionTest.xml',
                               encoding='utf-8', xml_declaration=True)
raise SystemExit(0 if suite.get('failures') == '0' else 1)
