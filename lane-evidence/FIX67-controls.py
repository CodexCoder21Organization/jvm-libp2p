from pathlib import Path
import subprocess, shutil, json, xml.etree.ElementTree as ET, datetime
repo=Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/FIX67')
out=repo.parent.parent/'out'
def run(label,expected):
    subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
    subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
    with (out/(label+'.log')).open('w') as log:
        r=subprocess.run(['timeout','20m','./gradlew','--no-daemon',':libp2p:cleanTest',':libp2p:test','--tests','io.libp2p.transport.RetryCloseInactiveReviewTest'],cwd=repo,stdout=log,stderr=subprocess.STDOUT)
    resultdir=out/(label+'-results')
    shutil.copytree(repo/'libp2p/build/test-results/test',resultdir,ignore=shutil.ignore_patterns('binary'),dirs_exist_ok=True)
    roots=[ET.parse(f).getroot() for f in resultdir.glob('TEST-*.xml')]
    summary={k:sum(int(r.attrib.get(k,0)) for r in roots) for k in ('tests','failures','errors','skipped')}
    summary.update(exit=r.returncode,head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    (out/(label+'.json')).write_text(json.dumps(summary,indent=2)+'\n')
    print(label,summary,flush=True)
    assert r.returncode==expected, summary
    assert summary['tests']==1 and summary['errors']==summary['skipped']==0, summary
    assert summary['failures']==(1 if expected else 0), summary
    if expected:
        failure=roots[0].find('.//failure')
        assert failure is not None and 'Peer closure must not replace a pending attempt' in failure.attrib.get('message',''),summary
for n in (2,3): run('FIX67-positive-'+str(n),0)
