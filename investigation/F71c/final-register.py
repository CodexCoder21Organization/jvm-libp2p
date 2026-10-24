import subprocess,pathlib,sys,json
root=pathlib.Path.cwd(); ev=root.parent.parent.parent/'out'/'F71c-evidence'
head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
classes=(ev/'final-class-list.txt').read_text().splitlines(); results=[]
prefix=sys.argv[1] if len(sys.argv)>1 else 'final'
for cls in classes:
 label=prefix+'-'+cls.rsplit('.',1)[1]
 print('START '+label,flush=True)
 with (ev/(label+'-runner.log')).open('w') as log:r=subprocess.run([sys.executable,str(ev/'run-class.py'),label,cls],stdout=log,stderr=subprocess.STDOUT)
 result=json.loads((ev/(label+'.json')).read_text())
 results.append(result); (ev/(prefix+'-register.json')).write_text(json.dumps(results,indent=2))
 print('END '+label+' exit='+str(r.returncode),flush=True)
 if subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()!=head:raise RuntimeError('HEAD changed during the final register')
print('REGISTER_COMPLETE '+str(len(results)),flush=True)
