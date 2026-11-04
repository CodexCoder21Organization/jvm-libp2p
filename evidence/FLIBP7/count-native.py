from pathlib import Path
import sys,json,xml.etree.ElementTree as E
root=Path(sys.argv[1]); cases=[]
for path in root.glob('*.xml'):
    tree=E.parse(path)
    for parent in tree.iter():
        for child in list(parent):
            if child.tag=='properties': parent.remove(child)
    tree.write(path,encoding='utf-8',xml_declaration=True)
    cases.extend(tree.findall('.//testcase'))
failed=[{'class':c.get('classname'),'name':c.get('name'),'message':(c.find('failure') if c.find('failure') is not None else c.find('error')).get('message','')} for c in cases if c.find('failure') is not None or c.find('error') is not None]
result={'tests':len(cases),'first_attempt_passes':len(cases)-len(failed)-sum(c.find('skipped') is not None for c in cases),'failures':failed,'skipped':sum(c.find('skipped') is not None for c in cases),'native_attempts_per_case':1}
root.with_suffix('.json').write_text(json.dumps(result,indent=2)+'\n')
print({k:v for k,v in result.items() if k!='failures'},'failures',len(failed))
