from pathlib import Path
import subprocess
import sys

checkout = Path(sys.argv[1])
mode = sys.argv[2]
root = checkout / 'build.gradle.kts'
props = checkout / 'gradle.properties'
if mode in ('original_memory', 'native-red'):
    subprocess.run(['git', 'restore', '--source=00dad34506398205174487a506c6cc6e4f8d2643', '--', 'build.gradle.kts', 'gradle.properties', 'examples/android-chatter/build.gradle'], cwd=checkout, check=True)
elif mode == 'budget':
    subprocess.run(['git', 'restore', '--source=6406e6ca7ce3dd3fa0b7092685432d02471a0642', '--', 'build.gradle.kts', 'gradle.properties'], cwd=checkout, check=True)
else:
    text = root.read_text()
    if mode == 'html_alias':
        before = 'dependsOn(dokkaHtmlTask)'
        assert before in text
        text = text.replace(before, 'dependsOn("dokkaGenerateHtml")', 1)
    elif mode == 'javadoc_alias':
        before = 'dependsOn(dokkaJavadocTask)'
        assert before in text
        text = text.replace(before, 'dependsOn("dokkaGenerateJavadoc")', 1)
    elif mode == 'archive':
        before = 'from(dokkaJavadocTask.flatMap { it.outputDirectory })'
        assert before in text
        text = text.replace(before, 'from(layout.buildDirectory.dir("emptyJavadoc"))')
    elif mode == 'metadata':
        before = 'implementation(kotlin("stdlib-jdk8", "1.6.21"))'
        assert before in text
        text = text.replace(before, 'implementation(kotlin("stdlib-jdk8"))')
        props.write_text(props.read_text().replace('kotlin.stdlib.default.dependency=false', ''))
        subprocess.run(['git', 'restore', '--source=00dad34506398205174487a506c6cc6e4f8d2643', '--', 'examples/android-chatter/build.gradle'], cwd=checkout, check=True)
    else:
        raise AssertionError(mode)
    root.write_text(text)
