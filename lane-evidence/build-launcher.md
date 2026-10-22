```python
from pathlib import Path
import datetime, json, os, signal, subprocess, sys, time

out = Path(__file__).parent
repo = out.parent / 'workspace/PUBLIBP2P'
start = time.monotonic()
process = None
maximum = int(Path('/sys/fs/cgroup/memory.max').read_text())
required_headroom = 2 * 1024 ** 3

def sample(phase, **extra):
    row = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), phase=phase,
               elapsed_seconds=round(time.monotonic() - start, 2),
               memory_current=int(Path('/sys/fs/cgroup/memory.current').read_text()),
               memory_max=maximum, **extra)
    with (out / 'PUBLIBP2P-build-memory.jsonl').open('a') as file:
        file.write(json.dumps(row) + '\n')
    print(json.dumps(row), flush=True)
    return row

def stop(signum, frame):
    raise SystemExit(128 + signum)

for signum in [signal.SIGTERM, signal.SIGINT]:
    signal.signal(signum, stop)
try:
    while True:
        row = sample('waiting_for_memory')
        if maximum - row['memory_current'] >= required_headroom:
            break
        if time.monotonic() - start >= 1200:
            sample('memory_wait_deadline')
            sys.exit(75)
        time.sleep(30)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() == 'e15e0f1947842be7ef6836c116c14ba47cc8eea1'
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo, text=True).strip()
    command = ['./gradlew', '--no-daemon', '--max-workers=1', '--console=plain', '-I', str(out / 'PUBLIBP2P-release.init.gradle'), '-PmavenArtifactId=jvm-libp2p', ':libp2p:jar', ':libp2p:generatePomFileForMavenJavaPublication', '--stacktrace']
    with (out / 'PUBLIBP2P-build.log').open('w') as log:
        process = subprocess.Popen(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (out / 'PUBLIBP2P-build-pgid.txt').write_text(str(process.pid) + '\n')
        sample('build_started', pid=process.pid, command=command)
        while True:
            try:
                code = process.wait(timeout=30)
                break
            except subprocess.TimeoutExpired:
                sample('building', pid=process.pid)
                if time.monotonic() - start >= 2670:
                    sample('build_deadline')
                    sys.exit(124)
    sample('build_finished', exit_code=code)
    (out / 'PUBLIBP2P-build-exit.txt').write_text(str(code) + '\n')
    sys.exit(code)
finally:
    if process is not None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
```
