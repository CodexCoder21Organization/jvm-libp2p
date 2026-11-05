from pathlib import Path
import hashlib,json,urllib.request
root=Path(__file__).resolve().parents[3]
e=Path(__file__).resolve().parent
b=root/'f93g-build';b.mkdir(exist_ok=True)
config=json.loads((e/'local-classpath.json').read_text())
for path in config['dependencies']+config['compiler']:
    if not Path(path).is_file():raise FileNotFoundError('Required cached dependency is absent: '+path)
def download(url,path,expected):
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
        path.write_bytes(urllib.request.urlopen(url,timeout=30).read())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==expected, 'Downloaded bytes differ: '+str(path)
download('https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-45/jvm-libp2p-1.3.0-codexcoder21-snapshot-45.jar',b/'snapshot45.jar','a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c')
console=Path('/tmp/flibp6-junit-console.jar')
download('https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar',console,'3fdfc37e29744a9a67dd5365e81467e26fbde0b7aa204e6f8bbe79eeaa7ae892')
(b/'classpath').write_text(str(b/'snapshot45.jar')+':'+':'.join(config['dependencies'])+':'+str(console))
(b/'compiler-classpath').write_text(':'.join(config['compiler']))
deps=json.loads((e/'compile-runtime-dependencies.json').read_text())
(b/'compile-classpath').write_text(str(b/'snapshot45.jar')+':'+':'.join(deps)+':'+str(console))
print('Verified snapshot45, JUnit1.13.4 and cached compiler/runtime inputs. Rebuild source overlays with compile.py and F93dGate with javac under with-slots.sh.')
