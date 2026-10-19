from pathlib import Path
import subprocess
b=Path(__file__).resolve().parent
s=(b/'analyze-minimal.py').read_text().replace("b/f'minimal-{i}.json'", "b/f'fixed-{i}.json'").replace("b/f'minimal-{i}.jfr'", "b/f'fixed-{i}.jfr'").replace("b/'minimal-summary.json'", "b/'fixed-summary.json'")
exec(compile(s,str(b/'analyze-minimal.py'),'exec'))
