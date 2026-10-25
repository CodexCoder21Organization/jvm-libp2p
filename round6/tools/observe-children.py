import pathlib,time,json,os,signal,threading
root=pathlib.Path(__file__).resolve().parent.parent
out=root/'round6/child-observations';out.mkdir(exist_ok=True)
seen={}; boot=time.clock_gettime(time.CLOCK_BOOTTIME);hz=os.sysconf('SC_CLK_TCK')
start=time.monotonic()
while not (root/'workspace/stop-observer').exists() and time.monotonic()-start<4500:
 for p in pathlib.Path('/proc').iterdir():
  if not p.name.isdigit():continue
  try:
   raw=(p/'cmdline').read_bytes()
   if b'/F71g/' not in raw:continue
   if not any(n in raw.split(b'\0') for n in [b'io.libp2p.security.noise.NoiseHandshakePathHarness',b'io.libp2p.security.noise.NoiseHandshakeStarvationHarness']):continue
   stat=(p/'stat').read_text().split();pid=int(p.name)
   age=time.clock_gettime(time.CLOCK_BOOTTIME)-float(stat[21])/hz
   record={'pid':pid,'age_seconds':age,'state':stat[2],'utime':stat[13],'stime':stat[14],'rss_pages':stat[23],'command':raw.decode(errors='replace').split('\0')}
   if pid not in seen:
    seen[pid]={'dumped':False};(out/f'{pid}-start.json').write_text(json.dumps(record,indent=2))
   (out/f'{pid}-last.json').write_text(json.dumps(record,indent=2))
   storm=b'io.libp2p.security.noise.NoiseHandshakeStarvationHarness' in raw.split(b'\0')
   threshold = 12 if storm else (7 if b'inline' in raw.split(b'\0') else 22 if b'sequential' in raw.split(b'\0') else 27)
   if age>threshold and not seen[pid]['dumped']:
    os.kill(pid,signal.SIGQUIT);seen[pid]['dumped']=True
    (out/f'{pid}-dump-signal.json').write_text(json.dumps(record,indent=2))
  except (FileNotFoundError,ProcessLookupError,PermissionError,IndexError):pass
 threading.Event().wait(1)
(out/'observer-ended.json').write_text(json.dumps({'tracked':seen,'elapsed':time.monotonic()-start}))
