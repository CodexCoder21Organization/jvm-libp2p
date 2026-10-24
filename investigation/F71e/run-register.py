import pathlib,subprocess,sys,json,time
root=pathlib.Path(__file__).resolve().parent
repo=root/'jvm-libp2p'; ev=repo/'investigation/F71e'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
for cls in (ev/'register-classes.txt').read_text().splitlines():
 if time.time() > 1792881300: print('INCOMPLETE: deadline before '+cls,flush=True);break
 label='register-'+cls.rsplit('.',1)[-1]
 if (ev/label/'result.json').exists(): raise RuntimeError('Register first attempt already exists: '+cls)
 if subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()!=head: raise RuntimeError('HEAD changed during register')
 print('START '+cls,flush=True)
 subprocess.run([sys.executable,str(root/'run-one.py'),label,cls],check=True)
 print('END '+cls,flush=True)
print('REGISTER_ENDED',flush=True)
