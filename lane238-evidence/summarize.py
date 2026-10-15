from pathlib import Path
import xml.etree.ElementTree as E
import json,sys
name=sys.argv[1]
selector=sys.argv[2] if len(sys.argv)>2 else 'lane238Trial*'
rows=[]
for p in sorted((Path('evidence')/name).glob(selector+'/TEST-*.xml')):
    root=E.parse(p).getroot()
    for case in root.findall('testcase'):
        fail=case.find('failure'); err=case.find('error'); skip=case.find('skipped')
        rows.append({'file':str(p),'class':case.get('classname'),'name':case.get('name'),'time':case.get('time'),'outcome':'FAIL' if fail is not None or err is not None else 'SKIP' if skip is not None else 'PASS'})
result={'name':name,'selector':selector,'tests':len(rows),'passed':sum(r['outcome']=='PASS' for r in rows),'failed':sum(r['outcome']=='FAIL' for r in rows),'skipped':sum(r['outcome']=='SKIP' for r in rows),'cases':rows}
Path('evidence',name+'-summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='cases'}))
