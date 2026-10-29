#!/usr/bin/env bash
set -euo pipefail
evidence=$(cd "$(dirname "$0")" && pwd)
"$evidence/with-slots.sh" 1 coursier fetch kompile.cli:kompile-cli:0.0.125 -r https://kotlin.directory --classpath > /tmp/flibp5-fetch-cp.log
python3 - <<'PY'
from pathlib import Path
lines=Path('/tmp/flibp5-fetch-cp.log').read_text().splitlines()
Path('/tmp/flibp5-classpath').write_text(next(x for x in reversed(lines) if '/kompile-cli-' in x))
PY
"$evidence/with-slots.sh" 1 javac -cp "$(cat /tmp/flibp5-classpath)" -d /tmp/flibp5-launcher "$evidence/SingleAttempt.java"
