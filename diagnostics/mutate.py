from pathlib import Path
import subprocess
import sys
checkout = Path(sys.argv[1])
mode = sys.argv[2]
root = checkout / 'build.gradle'
props = checkout / 'gradle.properties'
if mode in ('original_memory', 'native-red', 'budget'):
    source = '6406e6ca7ce3dd3fa0b7092685432d02471a0642' if mode == 'budget' else '00dad34506398205174487a506c6cc6e4f8d2643'
    for name in ['build.gradle', 'libp2p/build.gradle', 'interop-test-client/build.gradle']:
        (checkout / name).unlink()
    subprocess.run(['git', 'restore', '--source='+source, '--', 'build.gradle.kts', 'libp2p/build.gradle.kts', 'interop-test-client/build.gradle.kts', 'gradle.properties', 'examples/android-chatter/build.gradle'], cwd=checkout, check=True)
else:
    text = root.read_text()
    if mode == 'plugin_boundary':
        before = 'generator.task.runtimeClasspath.setFrom(sharedRuntime, sharedResourcesDirectory)'
        assert before in text
        text = text.replace(before, 'generator.task.runtimeClasspath.setFrom(sharedRuntime)')
    elif mode == 'compiler_boundary':
        before = 'commonFiles.contains(file) || !isDokkaPlugin(file)'
        assert before in text
        text = text.replace(before, '!isDokkaPlugin(file)')
    elif mode == 'no_service':
        before = 'usesService(dokkaWorkerBudget)'
        assert before in text
        text = text.replace(before, '// Service deliberately removed for this control')
    elif mode == 'buildscript_compiler':
        (checkout / 'compiler-control.gradle.kts').write_text('logger.lifecycle("Original Kotlin buildscript compiler loaded")\n')
        text += "\napply from: 'compiler-control.gradle.kts'\n"
    elif mode == 'eager_resolution':
        before = "gradle.taskGraph.whenReady { graph ->\n    if (!graph.allTasks.any { it instanceof DokkaGenerateTask }) return"
        assert before in text
        text = text.replace(before, 'gradle.projectsEvaluated {')
    elif mode == 'html_alias':
        before = 'dependsOn dokkaHtmlTask'
        assert before in text
        text = text.replace(before, "dependsOn 'dokkaGenerateHtml'", 1)
    elif mode == 'javadoc_alias':
        before = 'dependsOn dokkaJavadocTask'
        assert before in text
        text = text.replace(before, "dependsOn 'dokkaGenerateJavadoc'", 1)
    elif mode == 'archive':
        before = 'from dokkaJavadocTask.flatMap { task -> task.outputDirectory }'
        assert before in text
        text = text.replace(before, "from layout.buildDirectory.dir('emptyJavadoc')")
    elif mode == 'metadata':
        before = "implementation 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.6.21'"
        assert before in text
        text = text.replace(before, "implementation 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.25'")
        props.write_text(props.read_text().replace('kotlin.stdlib.default.dependency=false', ''))
        subprocess.run(['git', 'restore', '--source=00dad34506398205174487a506c6cc6e4f8d2643', '--', 'examples/android-chatter/build.gradle'], cwd=checkout, check=True)
    else:
        raise AssertionError(mode)
    root.write_text(text)
