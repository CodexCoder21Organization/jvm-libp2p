from pathlib import Path
import os,time,json,socket
root=Path(__file__).resolve().parent
finish=time.monotonic()+240
seen={}
while time.monotonic()<finish:
    endpoints={}
    for table in ['/proc/net/tcp','/proc/net/tcp6']:
        for line in Path(table).read_text().splitlines()[1:]:
            row=line.split()
            if row[3] not in ['01','02','03','08']: continue
            endpoints[row[9]]={'local':row[1],'remote':row[2],'state':row[3]}
    changed=False
    for proc in Path('/proc').glob('[0-9]*'):
        try:
            cwd=os.readlink(proc/'cwd')
            for fd in (proc/'fd').iterdir():
                try: link=os.readlink(fd)
                except OSError: continue
                if not link.startswith('socket:['): continue
                inode=link[8:-1]
                if inode in endpoints:
                    key=f'{proc.name}:{inode}'
                    if key not in seen:
                        seen[key]={'pid':proc.name,'cwd':cwd,'executable':os.readlink(proc/'exe'),**endpoints[inode]}
                        changed=True
        except (OSError,PermissionError): continue
    if changed: (root/'socket-owners.json').write_text(json.dumps(seen,indent=2))
    time.sleep(0.1)
print('Socket observation finished',len(seen),flush=True)
