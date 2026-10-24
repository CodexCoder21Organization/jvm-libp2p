import pathlib,subprocess,sys,json,time
script=pathlib.Path(__file__).resolve()
repo=script.parents[2] if script.parent.name=='F71e' and script.parent.parent.name=='investigation' else script.parent/'jvm-libp2p'
root=repo.parent; ev=repo/'investigation/F71e'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
for cls in (ev/'register-classes.txt').read_text().splitlines():
 if time.time() > 1792881300: print('INCOMPLETE: deadline before '+cls,flush=True);break
 label='register-'+cls.rsplit('.',1)[-1]
 if (ev/label/'result.json').exists(): raise RuntimeError('Register first attempt already exists: '+cls)
 if subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()!=head: raise RuntimeError('HEAD changed during register')
 print('START '+cls,flush=True)
 subprocess.run([sys.executable,str(script.with_name('run-one.py')),label,cls],check=True)
 print('END '+cls,flush=True)
print('REGISTER_ENDED',flush=True)
