from pathlib import Path
import json, os, shutil, subprocess, tempfile, xml.etree.ElementTree as E

run = os.environ['PROOF_RUN']
repo = 'CodexCoder21Organization/jvm-libp2p'
root = Path('evidence') / run
root.mkdir(parents=True, exist_ok=True)
# A failed test is still a result to preserve; the metadata and XML retain its verdict.
watch = subprocess.run(['gh', 'run', 'watch', run, '--repo', repo, '--interval', '60', '--exit-status'], timeout=1200)
meta = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/actions/runs/{run}']))
(root/'run.json').write_text(json.dumps(meta, indent=2))
with (root/'workflow.log').open('w') as f:
    log = subprocess.run(['gh', 'run', 'view', run, '--repo', repo, '--log'], stdout=f, stderr=subprocess.STDOUT)
with tempfile.TemporaryDirectory() as temp:
    download = subprocess.run(['gh', 'run', 'download', run, '--repo', repo,
        '--pattern', os.environ['PROOF_ARTIFACT'], '--dir', temp], capture_output=True, text=True)
    (root/'download.txt').write_text(download.stdout + download.stderr)
    for p in Path(temp).rglob('*'):
        if p.is_file() and (p.suffix in ('.xml','.txt','.log','.jsonl','.json')):
            q=root/'artifact'/p.relative_to(temp); q.parent.mkdir(parents=True,exist_ok=True); shutil.copy(p,q)
summary={'run':run,'url':meta['html_url'],'status':meta['status'],'conclusion':meta['conclusion'],
         'watch_exit':watch.returncode,'download_exit':download.returncode,'log_exit':log.returncode,'xml':[]}
for p in (root/'artifact').rglob('*.xml'):
    tree=E.parse(p).getroot()
    row={'path':str(p),'suite':tree.attrib,'cases':[]}
    for c in tree.iter('testcase'):
        row['cases'].append({'name':c.attrib.get('name'), 'classname':c.attrib.get('classname'),
            'failures':[{'message':f.attrib.get('message'),'text':f.text} for f in c.findall('failure')],
            'errors':len(c.findall('error')),'skipped':len(c.findall('skipped'))})
    summary['xml'].append(row)
(root/'summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary,indent=2),flush=True)
for cmd in [['git','config','user.name','Codex'],['git','config','user.email','codex@openai.com'],
            ['git','switch','-c',f'proof/w2-results232805-{run}'],['git','add',str(root)],
            ['git','commit','-m',f'Preserve existing proof results for run {run}'],
            ['git','push','origin',f'HEAD:refs/heads/proof/w2-results232805-{run}']]:
    subprocess.run(cmd,check=True)
