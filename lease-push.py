import subprocess,pathlib,sys,json
repo=pathlib.Path(sys.argv[1]).resolve(); branch=sys.argv[2]; marker=pathlib.Path(__file__).parent/(branch.replace('/','_')+'-remote-head.json')
expected=json.loads(marker.read_text())['sha'] if marker.exists() else ''
subprocess.run(['git','fetch','origin'],cwd=repo,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
rows=subprocess.check_output(['git','ls-remote','--heads','origin','refs/heads/'+branch],cwd=repo,text=True).strip()
actual=rows.split()[0] if rows else ''
if actual!=expected:raise SystemExit(f'STOP: {branch} moved: expected {expected!r}, actual {actual!r}')
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
subprocess.run(['git','push',f'--force-with-lease={branch}:{expected}','origin',f'HEAD:refs/heads/{branch}'],cwd=repo,check=True)
marker.write_text(json.dumps({'sha':head}));print('PUSHED_CHECKPOINT',branch,head)
