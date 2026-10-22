from pathlib import Path
import hashlib
import re
import subprocess
import sys

repo = Path(subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip())
label = sys.argv[1]
output = repo / '.review' / 'R66b-noise' / label
output.mkdir(parents=True, exist_ok=True)
class_pattern = re.compile(
    r'(?:io/libp2p/mux/yamux/YamuxStreamMuxer[^/]*|'
    r'io/libp2p/mux/mplex/MplexStreamMuxer[^/]*|'
    r'io/libp2p/core/Stream|io/libp2p/core/mux/StreamMuxer[^/]*)\.class'
)
classes = []
classes_root = repo / 'libp2p' / 'build' / 'classes'
for root in classes_root.glob('**'):
    if not root.is_dir():
        continue
    for path in root.rglob('*.class'):
        parts = path.relative_to(classes_root).parts
        class_path = '/'.join(parts[2:])
        if class_pattern.fullmatch(class_path):
            classes.append(path)
classes = sorted(set(classes))
with (output / 'class-sha256.txt').open('w') as hashes:
    for path in classes:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        hashes.write(f'{digest}  {path.relative_to(repo).as_posix()}\n')
results = []
for path in classes:
    r = subprocess.run(['javap', '-public', '-s', str(path)], capture_output=True, text=True, check=False)
    results.append((path, r))
with (output / 'javap-public-s.txt').open('w') as descriptors:
    for path, result in results:
        descriptors.write(f'file={path.relative_to(repo).as_posix()} exit_status={result.returncode}\n')
        if result.returncode == 0:
            descriptors.write(result.stdout)
        descriptors.write('\n')
(output / 'class-evidence.meta').write_text(
    f'revision={subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()}\n'
    f'class_file_count={len(classes)}\n'
    f'javap_error_count={sum(1 for _, result in results if result.returncode != 0)}\n'
)
print(f'{label} classes={len(classes)} javap_errors={sum(1 for _, result in results if result.returncode != 0)}')
