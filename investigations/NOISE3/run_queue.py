from pathlib import Path
import subprocess, concurrent.futures, json, time
root=Path(__file__).resolve().parents[2]
cp=(root/'libp2p/build/noise3-classpath.txt').read_text()
out=root/'investigations/NOISE3/queue'
out.mkdir(exist_ok=True)
def run(n):
    r=subprocess.run(['java','--add-exports','java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED','-javaagent:'+str(root.parent/'queue-trace.jar'),'-Dnoise3.trace=true','-cp',cp,'io.libp2p.security.noise.NoiseHandshakePathHarness','sequential'],capture_output=True,text=True,timeout=30)
    (out/f'{n:03}.log').write_text(r.stdout+r.stderr)
    print(json.dumps({'run':n,'exit':r.returncode}),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(run,range(1,101)))
