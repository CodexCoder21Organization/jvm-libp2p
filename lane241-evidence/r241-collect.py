from pathlib import Path
import json,shutil,sys
label=sys.argv[1]; pattern=sys.argv[2]
base=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad')
repo=base/'workspace/lane241/jvm-libp2p'; dst=base/'workspace/lane241/evidence/lane241-evidence'
assert (base/'out'/('r241-'+label+'-result.json')).exists(), 'Run not terminal'
for p in sorted((repo/'libp2p/build/test-results').glob(pattern)):
    t=dst/(label+'-'+p.name+'-xml');t.mkdir(exist_ok=True)
    for x in p.glob('*.xml'): shutil.copy2(x,t/x.name)
for p in (base/'out').glob('r241-'+label+'*'):shutil.copy2(p,dst/p.name)
