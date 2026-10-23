from pathlib import Path
import subprocess, datetime, json, shutil, xml.etree.ElementTree as ET
repo=Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/FIX67')
out=repo.parent.parent/'out'
def utc(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def note(s):
    with (out/'FIX67.md').open('a') as f: f.write('\n'+utc()+' OBSERVED: '+s+'\n')
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
cmd=['flock','-w','5400','/tmp/claude-501/complete-suite.lock','timeout','45m','./gradlew','--no-daemon','cleanTest','test','--continue']
note('Full suite submitted once at head '+head+'; lock wait capped at 90 minutes, suite command capped at 45 minutes, no JVM tuning. Command: '+' '.join(cmd))
with (out/'FIX67-full.log').open('w') as log, (out/'FIX67-full-memory.jsonl').open('a') as mem:
    p=subprocess.Popen(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
    for tick in range(140):
        row={'utc':utc(),'memory_current':int(Path('/sys/fs/cgroup/memory.current').read_text()),'memory_events':Path('/sys/fs/cgroup/memory.events').read_text(),'pid':p.pid}
        mem.write(json.dumps(row)+'\n'); mem.flush()
        print('full-suite',row['utc'],'memory',row['memory_current'],'pid',p.pid,flush=True)
        if tick%5==0: note('Full-suite command active; memory.current='+str(row['memory_current'])+' bytes. '+('Gradle output exists.' if (out/'FIX67-full.log').stat().st_size else 'Waiting for machine-wide suite lock; no Gradle output yet.'))
        try: code=p.wait(timeout=60); break
        except subprocess.TimeoutExpired: pass
    else:
        p.terminate(); code=p.wait(timeout=30); note('PROBLEM: full command exceeded its outer tracking cap.')
    mem.write(json.dumps({'utc':utc(),'memory_current':int(Path('/sys/fs/cgroup/memory.current').read_text()),'memory_events':Path('/sys/fs/cgroup/memory.events').read_text(),'exit':code})+'\n')
summary={'head':head,'exit':code,'utc':utc(),'tests':0,'failures':0,'errors':0,'skipped':0}
failures=[]; stacks=[]
for f in repo.glob('**/build/test-results/test/TEST-*.xml'):
    dest=out/'FIX67-full-results'/f.relative_to(repo)
    dest.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(f,dest)
    root=ET.parse(f).getroot()
    for k in ('tests','failures','errors','skipped'): summary[k]+=int(root.attrib.get(k,0))
    for t in root.findall('testcase'):
        for failure in list(t.findall('failure'))+list(t.findall('error')):
            ident=(t.attrib['classname'],t.attrib['name']); failures.append(ident); stacks.append(str(ident)+'\n'+(failure.text or ''))
summary['passed']=summary['tests']-summary['failures']-summary['errors']-summary['skipped']
(out/'FIX67-full-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
(out/'FIX67-full-stacks.txt').write_text('\n\n'.join(stacks))
(out/'FIX67-full-failures.json').write_text(json.dumps(sorted(failures),indent=2)+'\n')
base=[]
for f in (out/'lane-evidence/REV67-develop-control-results').glob('TEST-*.xml'):
    for t in ET.parse(f).getroot().findall('testcase'):
        if t.find('failure') is not None or t.find('error') is not None: base.append((t.attrib['classname'],t.attrib['name']))
compare={'baseline_count':len(base),'full_count':len(failures),'equal':set(base)==set(failures),'extra':sorted(set(failures)-set(base)),'missing':sorted(set(base)-set(failures))}
(out/'FIX67-baseline-comparison.json').write_text(json.dumps(compare,indent=2)+'\n')
print(summary,compare,flush=True); note('Full-suite result '+json.dumps(summary)+'. Exact unchanged-develop failure-set comparison '+json.dumps(compare)+'. Full stacks preserved.')
assert code==1 and summary['failures']==20 and summary['errors']==0 and compare['equal'],(summary,compare)
