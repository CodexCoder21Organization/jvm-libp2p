import subprocess, sys, os
from pathlib import Path
name=sys.argv[1]
subprocess.run(['git','fetch','origin'],cwd='jvm-libp2p',check=True)
subprocess.run(['git','rebase','origin/develop'],cwd='jvm-libp2p',check=True)
base=['jvm-libp2p/gradlew','-p','jvm-libp2p','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx2g','-Pkotlin.compiler.execution.strategy=in-process']
if sys.argv[2]=='trials':
    args=['-I',str(Path('repeat.init.gradle').resolve()),'--continue']+[f':libp2p:lane238Trial{i}' for i in range(1,21)]
else: args=sys.argv[2:]
env=dict(os.environ,JAVA_OPTS='-Xmx2g')
code=subprocess.run([sys.executable,'bounded.py','900',f'evidence/{name}.log']+base+args,env=env).returncode
out=Path('evidence')/name
out.mkdir(exist_ok=True)
import shutil
for path in Path('jvm-libp2p/libp2p/build/test-results').glob('*'):
    if path.is_dir(): shutil.copytree(path,out/path.name,ignore=shutil.ignore_patterns('binary'),dirs_exist_ok=True)
sys.exit(code)
