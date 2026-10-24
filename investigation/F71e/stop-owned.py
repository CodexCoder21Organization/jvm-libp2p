from pathlib import Path
import os,time,signal,json
root=Path(__file__).resolve().parent.parent;e=root/'workspace/jvm-libp2p/investigation/F71e'
def owned():
 rows=[]
 for p in Path('/proc').iterdir():
  if not p.name.isdigit():continue
  try:
   args=(p/'cmdline').read_bytes().split(b'\0')
   if not args or not args[0].endswith(b'java'):continue
   cwd=(p/'cwd').resolve(strict=True);stat=(p/'stat').read_text().split()
   if root not in (cwd,*cwd.parents) and b'-DF71e.compiler=true' not in args:continue
   if stat[2]=='Z':continue
   rows.append({'pid':int(p.name),'cwd':str(cwd),'state':stat[2],'start_ticks':stat[21],'compiler_marker':b'-DF71e.compiler=true' in args})
  except (OSError,ValueError):pass
 return rows
before=owned()
for r in before:
 try:os.kill(r['pid'],signal.SIGTERM)
 except ProcessLookupError:pass
end=time.monotonic()+5
while owned() and time.monotonic()<end:time.sleep(.1)
for r in owned():
 try:os.kill(r['pid'],signal.SIGKILL)
 except ProcessLookupError:pass
end=time.monotonic()+3
while owned() and time.monotonic()<end:time.sleep(.1)
after=owned();(e/'final-process-audit.json').write_text(json.dumps({'before_final_signals':before,'remaining_started_jvms':after},indent=2));print('REMAINING_STARTED_JVMS='+str(len(after)));assert not after
