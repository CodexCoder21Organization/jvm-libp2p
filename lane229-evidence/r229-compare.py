import json,sys
from pathlib import Path
from collections import defaultdict
out=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out')
combined={}
for label in ['base-broader','branch-broader']:
 data=json.loads((out/f'r229-{label}-summary.json').read_text()); cases=defaultdict(lambda:{'passed':0,'failed':0}); total=defaultdict(int)
 for task,d in data.items():
  if 'counts' not in d: continue
  for k,v in d['counts'].items():total[k]+=v
  failed={(f['class'],f['name']) for f in d['failures']}
  for c in d['cases']:
   key=(c['class'],c['name']); cases['.'.join(key)]['failed' if key in failed else 'passed']+=1
 combined[label]={'counts':dict(total),'cases':dict(cases)}
(out/'r229-broader-comparison.json').write_text(json.dumps(combined,indent=2))
lines=['| Case | PR pass/fail | Base pass/fail |','|---|---:|---:|']
for key in sorted(set(combined['base-broader']['cases'])|set(combined['branch-broader']['cases'])):
 b=combined['branch-broader']['cases'].get(key,{'passed':0,'failed':0}); a=combined['base-broader']['cases'].get(key,{'passed':0,'failed':0}); lines.append(f"| {key} | {b['passed']}/{b['failed']} | {a['passed']}/{a['failed']} |")
(out/'r229-broader-comparison.md').write_text('\n'.join(lines)+'\n')
print('\n'.join(lines))
for side,data in combined.items():print(side,data['counts'])
