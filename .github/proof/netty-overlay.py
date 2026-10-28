from pathlib import Path
import shutil, subprocess, urllib.request, os

root=Path('/tmp/w2-netty-overlay')
root.mkdir(exist_ok=True)
deps=root/'deps'; deps.mkdir(exist_ok=True)
version='4.2.10.Final'
for artifact in ['netty-common','netty-buffer','netty-resolver','netty-transport']:
    urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/io/netty/{artifact}/{version}/{artifact}-{version}.jar', deps/f'{artifact}.jar')
for name in ['AbstractChannel.java','DefaultChannelPipeline.java']:
    p=root/f'transport/src/main/java/io/netty/channel/{name}'; p.parent.mkdir(parents=True,exist_ok=True)
    urllib.request.urlretrieve(f'https://raw.githubusercontent.com/netty/netty/netty-{version}/transport/src/main/java/io/netty/channel/{name}',p)
patch=Path(os.environ['NETTY_HOOK_PATCH']).resolve()
subprocess.run(['git','apply','--include=transport/src/main/java/io/netty/channel/AbstractChannel.java','--include=transport/src/main/java/io/netty/channel/DefaultChannelPipeline.java',str(patch)],cwd=root,check=True)
classes=root/'classes'; classes.mkdir(exist_ok=True)
subprocess.run(['javac','--release','8','-classpath',str(deps/'*'),'-d',str(classes),*map(str,root.glob('transport/src/main/java/io/netty/channel/*.java'))],check=True)
proof='4.2.10-w2-auto-read-proof'
repo=root/'repo'; dest=repo/f'io/netty/netty-transport/{proof}'; dest.mkdir(parents=True,exist_ok=True)
jar=dest/f'netty-transport-{proof}.jar'; shutil.copy(deps/'netty-transport.jar',jar)
subprocess.run(['jar','uf',str(jar),'-C',str(classes),'.'],check=True)
abi=subprocess.check_output(['javap','-p','-classpath',str(jar),'io.netty.channel.DefaultChannelPipeline'],text=True)
assert 'protected boolean shouldAutoRead();' in abi, abi
# Use the same dependency-management pin the real consumer will use after publication.
versions=Path('versions.gradle')
configuration=versions.read_text().replace("            entry 'netty-transport'\n",'')
configuration=configuration.replace('        dependencySet(group: "io.netty", version: "4.2.10.Final") {',
    f'        dependency "io.netty:netty-transport:{proof}"\n        dependencySet(group: "io.netty", version: "4.2.10.Final") {{')
versions.write_text(configuration)

pom=urllib.request.urlopen(f'https://repo.maven.apache.org/maven2/io/netty/netty-transport/{version}/netty-transport-{version}.pom').read().decode()
pom=pom.replace('<artifactId>netty-transport</artifactId>',f'<artifactId>netty-transport</artifactId>\n  <version>{proof}</version>',1).replace('${project.version}',version)
(dest/f'netty-transport-{proof}.pom').write_text(pom)
Path('/tmp/w2-netty-overlay.init.gradle').write_text('''settingsEvaluated { settings ->
 settings.dependencyResolutionManagement.repositories.maven { url = uri("REPO") }
}
'''.replace('REPO',str(repo)).replace('VERSION',proof))
print('Built temporary source-based Netty transport overlay:',jar)
