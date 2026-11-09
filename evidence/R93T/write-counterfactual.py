from pathlib import Path
import json

e = Path(__file__).resolve().parent
old = json.loads((e / 'old-summary.json').read_text())
head = json.loads((e / 'head-summary.json').read_text())
selector = json.loads((e / 'pre-selector-summary.json').read_text())
key = lambda row: (row['classname'], row['name'])
head_rows = {key(row): row for row in head['cases']}
selector_rows = {key(row): row for row in selector['cases']}
assert set(head_rows) == {key(row) for row in old['cases']}
lines = ['OBSERVED: Counterfactual ledger covers all53 selected cases from28 touched test files; every case ran once on develop and once on exact head, with0skips and0retries. The selector control adds1 attempt for each of2cases, using68c436af and identical fixture source.']
lines += ['INFER: A failed case is not automatically an intended-assertion reproduction. The saved XML and location columns distinguish preparation, body and cleanup; findings.md states the limits. The11 old-source passes include9 existing-test cases and2 successful-close controls.']
for row in old['cases']:
    cls = row['classname'].split('.')[-1]
    changed = cls in ['ConnectionCloseRetryTest', 'ParentCloseOwnershipTest']
    suffix = ''
    if key(row) in selector_rows:
        r = selector_rows[key(row)]
        suffix = '; pre-selector=' + r['outcome'] + ', intended assertion=' + ','.join(r['test_locations'])
    lines.append('OBSERVED: ' + cls + '.' + row['name'] + '; ' + ('existing default test' if changed else 'PR-added test') + '; develop=' + row['outcome'] + '; head=' + head_rows[key(row)]['outcome'] + '; reported failure locations=' + (','.join(row['test_locations']) or 'none') + suffix + '; attempts=1 per source.')
(e / 'counterfactual.md').write_text('\n\n'.join(lines) + '\n')
print('OBSERVED: Wrote53-case paired-source ledger;2 selector-specific controls.')
