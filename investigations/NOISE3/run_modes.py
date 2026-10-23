from pathlib import Path
import subprocess, concurrent.futures, json, time, sys
root=Path(__file__).resolve().parents[2]
cp=(root/'libp2p/build/noise3-classpath.txt').read_text()
out=root/'investigations/NOISE3/trace'
out.mkdir(exist_ok=True)
def run(item):
    mode,n=item
    start=time.monotonic()
    result=subprocess.run(['java','-Dnoise3.trace=true','-cp',cp,'io.libp2p.security.noise.NoiseHandshakePathHarness',mode],capture_output=True,text=True,timeout=60)
    name=f'{mode}{n:03}'
    (out/(name+'.log')).write_text(result.stdout+result.stderr)
    record={'mode':mode,'run':n,'exit':result.returncode,'seconds':round(time.monotonic()-start,3)}
    (out/(name+'.json')).write_text(json.dumps(record))
    print(json.dumps(record),flush=True)
    return record
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(run,[(sys.argv[1],i) for i in range(int(sys.argv[3]) if len(sys.argv)>3 else 1,(int(sys.argv[3]) if len(sys.argv)>3 else 1)+int(sys.argv[2]))]))
