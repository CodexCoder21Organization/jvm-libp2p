import pathlib,subprocess,os,signal,select,json,re,time
root=pathlib.Path.cwd().resolve();ev=root.parent.parent.parent/'out'/'F71c-evidence'
registry='/tmp/F71c-gradle-home'
def cmdline(pid):
 try:return pathlib.Path('/proc',str(pid),'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
 except (OSError,ProcessLookupError):return ''
def owned_java():
 found={}
 for p in pathlib.Path('/proc').iterdir():
  if not p.name.isdigit():continue
  pid=int(p.name);c=cmdline(pid)
  if not c:continue
  exe=c.split(' ',1)[0]
  if pathlib.Path(exe).name!='java':continue
  try:cwd=pathlib.Path('/proc',p.name,'cwd').resolve()
  except OSError:cwd=pathlib.Path('/')
  if registry in c or str(root) in c or cwd==root or root in cwd.parents:found[pid]=c
 return found
before=owned_java();print('OWNED_JAVA_BEFORE',list(before),flush=True)
for pid,original in before.items():
 if cmdline(pid)!=original:continue
 try:
  fd=os.pidfd_open(pid)
  signal.pidfd_send_signal(fd,signal.SIGTERM)
  ready,_,_=select.select([fd],[],[],5)
  if not ready:
   signal.pidfd_send_signal(fd,signal.SIGKILL)
   select.select([fd],[],[],5)
  os.close(fd)
 except ProcessLookupError:pass
raw=subprocess.check_output(['ps','-eo','pid,args','-ww'],text=True)
lines=[]
for line in raw.splitlines():
 m=re.match(r'\s*(\d+)\s+(.*)',line)
 if not m:lines.append(line);continue
 pid=int(m.group(1));args=m.group(2)
 if pid not in before and str(root) not in args and registry not in args:
  args=args.split(' ',1)[0]+' [unrelated arguments omitted]'
 args=re.sub(r'gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+','[redacted]',args)
 args=re.sub(r'(Authorization:\s*(?:Bearer|token)\s+)\S+',r'\1[redacted]',args,flags=re.I)
 lines.append(str(pid)+' '+args)
(ev/'final-ps.txt').write_text('\n'.join(lines)+'\n')
after=owned_java();(ev/'owned-process-audit.json').write_text(json.dumps({'before':before,'remaining':after},indent=2));print('OWNED_JAVA_REMAINING',list(after),flush=True)
if after:raise RuntimeError('Task-owned JVMs remain after cleanup')
