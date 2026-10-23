from pathlib import Path
import xml.etree.ElementTree as E
import json,re
p=Path(__file__).resolve().parent
out={}
for label,folder in [('head','jvm-libp2p'),('base','base')]:
    runs=[]
    for f in sorted((p/folder/'libp2p/build/test-results').glob('r69Run*/*.xml')):
        r=E.parse(f).getroot()
        row={'run':f.parent.name,'tests':int(r.get('tests','0')),'red':int(r.get('failures','0')),'errors':int(r.get('errors','0')),'skipped':int(r.get('skipped','0')),'retry_entries':sum(1 for x in r.iter() if x.tag in ['flakyFailure','rerunFailure','rerunError']),'red_cases':[],'new_cases':0,'new_red':0,'silent_message2_cases':0,'silent_message2_red':0}
        for c in r.findall('testcase'):
            red=c.find('failure') is not None or c.find('error') is not None
            if c.get('name','').startswith('repetition'):
                row['new_cases']+=1;row['new_red']+=int(red)
            if c.get('name','').startswith('initiator times out silent responder'):
                row['silent_message2_cases']+=1;row['silent_message2_red']+=int(red)
            if red:
                e=c.find('failure') if c.find('failure') is not None else c.find('error')
                row['red_cases'].append({'name':c.get('name'),'type':e.get('type'),'source_lines':re.findall(r'NoiseHandshakeOffEventLoopTest.kt:(\d+)',e.text or '')[:3]})
        runs.append(row)
    keys=['tests','red','errors','skipped','retry_entries','new_cases','new_red','silent_message2_cases','silent_message2_red']
    out[label]={'runs':len(runs),'all_cases_green_runs':sum(x['red']==0 and x['errors']==0 and x['skipped']==0 for x in runs),**{k:sum(x[k] for x in runs) for k in keys},'details':runs}
(p/'review-progress.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({label:{k:v for k,v in data.items() if k!='details'} for label,data in out.items()}))
