import sys,json,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1]); label=sys.argv[2]; result={}; yamux=[]
for directory in sorted(root.glob('*-xml')):
    if not directory.name.startswith(label): continue
    tests=[]
    for xml in sorted(directory.glob('*.xml')):
        suite=ET.parse(xml).getroot()
        for case in suite.findall('testcase'):
            fail=case.find('failure'); err=case.find('error'); item={'class':case.get('classname'),'name':case.get('name'),'seconds':case.get('time'),'passed':fail is None and err is None,'stack':None if fail is None and err is None else (fail if fail is not None else err).text}
            tests.append(item)
            if item['class']=='io.libp2p.mux.ChildReadPauseTcpTest' and item['name']=='[2] yamux':
                yamux.append({'trial':directory.name,**item})
                if not item['passed']:
                    (root/(directory.name+'-yamux-failure.log')).write_text('\n'.join([item['stack'] or '',suite.findtext('system-out') or '',suite.findtext('system-err') or '']))
    result[directory.name]={'total':len(tests),'passed':sum(x['passed'] for x in tests),'failures':[x for x in tests if not x['passed']]}
summary={'label':label,'yamux':yamux,'yamuxPassed':sum(x['passed'] for x in yamux),'yamuxFailed':sum(not x['passed'] for x in yamux),'tasks':result}
(root/(label+'-summary.json')).write_text(json.dumps(summary,indent=2))
print(json.dumps({k:v for k,v in summary.items() if k!='tasks'},indent=2))
