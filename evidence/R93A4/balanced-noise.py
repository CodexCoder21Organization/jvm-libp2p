from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from datetime import datetime, timezone
from pathlib import Path
import json
import subprocess
import time

evidence = Path(__file__).resolve().parent
lane = evidence.parents[2]
arms = {'head': '-', 'b3fa5042': 'ordering-classes', 'develop': 'develop-classes'}
last_start = datetime(2026, 11, 14, 11, 17, tzinfo=timezone.utc).timestamp()

def count(arm):
    path = evidence / ('noise-' + arm + '-summary.json')
    return len(json.loads(path.read_text())) if path.exists() else 0

def run_one(arm):
    command = ['python3', str(evidence / 'noise-arms.py'), arm, arms[arm], '--once']
    result = subprocess.run(command, cwd=lane.parent)
    return arm, result.returncode

with ThreadPoolExecutor(max_workers=2) as pool:
    pending = {}
    exhausted = False
    while True:
        available = [arm for arm in arms if arm not in pending.values() and count(arm) < 10]
        while len(pending) < 2 and available and time.time() < last_start and not exhausted:
            arm = min(available, key=lambda arm: (count(arm), list(arms).index(arm)))
            pending[pool.submit(run_one, arm)] = arm
            available.remove(arm)
        if not pending:
            break
        complete, _ = wait(pending, timeout=30, return_when=FIRST_COMPLETED)
        for future in complete:
            arm, code = future.result()
            del pending[future]
            print(json.dumps({'arm': arm, 'class_runs': count(arm), 'runner_exit': code}), flush=True)
            if code == 75:
                exhausted = True
    print(json.dumps({'completed_class_runs': {arm: count(arm) for arm in arms}}), flush=True)
