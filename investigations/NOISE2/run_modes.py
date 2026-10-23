from pathlib import Path
import subprocess, concurrent.futures, json, time, signal, threading
stop=threading.Event()
signal.signal(signal.SIGTERM, lambda *_: stop.set())
root=Path(__file__).resolve().parents[2]
cp=(root/'libp2p/build/noise2-classpath.txt').read_text()
out=root/'investigations/NOISE2/addresses'
out.mkdir(exist_ok=True)
def run(mode):
    records=[]
    for n in range(1,101):
        if stop.is_set(): return records
        start=time.monotonic()
        r=subprocess.run(['java','-Dnoise2.trace=true','-cp',cp,'io.libp2p.security.noise.NoiseHandshakePathHarness',mode],capture_output=True,text=True,timeout=80)
        log=out/f'{mode}{n:03}.log'
        log.write_text(r.stdout+r.stderr)
        record={'mode':mode,'run':n,'exit':r.returncode,'seconds':round(time.monotonic()-start,3),'log':log.name}
        with (out/f'{mode}.jsonl').open('a') as f:f.write(json.dumps(record)+'\n')
        records.append(record)
        if n%10==0: print(json.dumps({'mode':mode,'completed':n,'red':sum(x['exit']!=0 for x in records)}),flush=True)
    return records
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    list(pool.map(run,['sequential','saturated']))
