import pathlib,time,json,subprocess,threading,os
root=pathlib.Path(__file__).resolve().parent; dest=root/'evidence/F71f/observations';dest.mkdir(exist_ok=True)
end=time.time()+3600; seen={}; dumps=set();stop=threading.Event()
while time.time()<end and not (root/'stop-observer').exists():
 records=[]
 for proc in pathlib.Path('/proc').iterdir():
  if not proc.name.isdigit():continue
  try:
   if (proc/'comm').read_text().strip()!='java':continue
   args=(proc/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
   cwd=str((proc/'cwd').resolve())
   if str(root) not in args and str(root) not in cwd:continue
   stat=(proc/'stat').read_text().split();pid=int(proc.name)
   record={'time':time.time(),'pid':pid,'ppid':int(stat[3]),'cpu_ticks':int(stat[13])+int(stat[14]),'state':stat[2],'rss_pages':int(stat[23]),'cwd':cwd,'role':'probe' if 'NoiseAlgorithmReadinessProbe' in args else 'worker' if 'GradleWorkerMain' in args else 'compiler' if 'KotlinCompileDaemon' in args else 'gradle'}
   records.append(record);seen.setdefault(pid,time.time())
   if record['role']=='probe':
    for token in args.split():
     if token.startswith('-javaagent:'):
      output=pathlib.Path(token[len('-javaagent:'):].removesuffix('.agent.jar'))
      if output.exists(): (dest/f'probe-{pid}.log').write_bytes(output.read_bytes())
    age=time.time()-seen[pid]
    milestone=15 if age>=15 and age<24 else 24 if age>=24 else None
    if milestone and (pid,milestone) not in dumps:
     dumps.add((pid,milestone))
     with (dest/f'threads-{pid}-{milestone}.txt').open('w') as f:
      try:subprocess.run(['jcmd',str(pid),'Thread.print','-l'],stdout=f,stderr=subprocess.STDOUT,timeout=5)
      except subprocess.TimeoutExpired:f.write('Thread print exceeded 5-second diagnostic bound\n')
  except (OSError,ValueError,ProcessLookupError):continue
 with (dest/'process-samples.jsonl').open('a') as f:f.write(json.dumps({'time':time.time(),'load':os.getloadavg(),'processes':records})+'\n')
 stop.wait(1)
