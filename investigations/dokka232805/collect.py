from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

run = os.environ['GITHUB_RUN_ID']
repo = os.environ['GITHUB_REPOSITORY']
root = Path('evidence') / run
root.mkdir(parents=True, exist_ok=True)
meta = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/actions/runs/{run}/jobs']))
(root / 'jobs.json').write_text(json.dumps(meta, indent=2))
with tempfile.TemporaryDirectory() as temp:
    subprocess.run(['gh', 'run', 'download', run, '--repo', repo,
                    '--pattern', 'dokka-budget232805-*', '--dir', temp], check=True)
    for source in Path(temp).rglob('*'):
        if source.is_file() and source.suffix in ('.xml', '.txt', '.log', '.jsonl', '.json', '.diff'):
            target = root / 'artifact' / source.relative_to(temp)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
summary = {'run': run, 'url': f'https://github.com/{repo}/actions/runs/{run}', 'xml': []}
for path in root.rglob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    summary['xml'].append({'path': str(path), 'suite': suite.attrib,
        'failures': [node.text for node in suite.iter('failure')]})
(root / 'summary.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2), flush=True)
source_pr = json.loads(subprocess.check_output(['gh', 'pr', 'view',
    f'https://github.com/{repo}/pull/76', '--json', 'state,headRefOid']))
(root / 'source-pr.json').write_text(json.dumps(source_pr, indent=2))
assert source_pr['state'] == 'OPEN', 'The source pull request is no longer open; stop before pushing.'
for command in [['git', 'config', 'user.name', 'Codex'], ['git', 'config', 'user.email', 'codex@openai.com'],
                ['git', 'switch', '-c', f'proof/w2-dokka232805-results-{run}'],
                ['git', 'add', '-f', str(root)],
                ['git', 'commit', '-m', f'Preserve documentation proof results for run {run}'],
                ['git', 'push', 'origin', f'HEAD:refs/heads/proof/w2-dokka232805-results-{run}']]:
    subprocess.run(command, check=True)
