import json,subprocess,pathlib
p=pathlib.Path(__file__).resolve().parent/"round8/noise-baseline"
cmd=json.loads((p/"compile-command.json").read_text())
with (p/"compile.log").open("w") as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=180)
print("BASELINE_COMPILE_EXIT",r.returncode,flush=True)
raise SystemExit(r.returncode)
