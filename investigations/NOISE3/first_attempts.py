from pathlib import Path
import concurrent.futures, subprocess, json, time, sys
root = Path(__file__).resolve().parents[2]
cp = (root / "libp2p/build/noise3-classpath.txt").read_text() + ":" + str(root / "investigations/NOISE3/classes")
mode, count, label = sys.argv[1], int(sys.argv[2]), sys.argv[3]
out = root / "investigations/NOISE3/results" / label
out.mkdir(parents=True, exist_ok=True)
def run(n):
    started = time.monotonic()
    with (out / f"{n:03}.log").open("w") as log:
        result = subprocess.run(["java", "-Xmx384m", "-cp", cp, "FirstAttemptNoise", mode, str(out / f"{n:03}.xml")], stdout=log, stderr=subprocess.STDOUT, timeout=120)
    record = {"run": n, "exit": result.returncode, "seconds": round(time.monotonic()-started, 3)}
    (out / f"{n:03}.json").write_text(json.dumps(record)+"\n")
    print(json.dumps(record), flush=True)
    return record
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    records = list(pool.map(run, range(1, count+1)))
sys.exit(0 if all(r["exit"] == 0 for r in records) else 1)
