import json,sys
from pathlib import Path
parsed=json.loads(Path(sys.argv[1]).read_text())
register={r['testName']:r for r in json.loads(Path(sys.argv[2]).read_text())}
rows=[]
for suite in parsed:
 for failure in suite['failures']:
  name=failure['name'].rsplit('.',1)[-1]
  old=register.get(name)
  trace=failure.get('trace') or ''
  line=trace.splitlines()[0] if trace.splitlines() else ''
  oldtrace=(old.get('originalError') or '') if old else ''
  oldline=oldtrace.splitlines()[0] if oldtrace.splitlines() else ''
  rows.append({'testName':name,'currentXml':suite['xml'],'currentErrorFirstLine':line,'registerNameMatch':old is not None,'errorFirstLineMatch':bool(old and line==oldline),'registerErrorFirstLine':oldline,'registerClassification':old.get('classification') if old else None,'registerMainPassed':old.get('mainAttempt',{}).get('passed') if old else None,'currentFullTrace':trace})
print(json.dumps({'failures':len(rows),'nameMatches':sum(r['registerNameMatch'] for r in rows),'firstLineMatches':sum(r['errorFirstLineMatch'] for r in rows),'unmatched':[r['testName'] for r in rows if not r['registerNameMatch']],'rows':rows},indent=2))
