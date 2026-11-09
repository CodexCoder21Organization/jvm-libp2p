#!/usr/bin/env bash
set -u
set -o pipefail
for phase in repetition forced-red; do
  git fetch origin develop && git rebase origin/develop || exit 2
  if [ "$phase" = repetition ]; then selector='io.libp2p.network.*CancellationRepetitionEvidenceTest'; else selector='io.libp2p.network.*CancellationOrderingEvidenceTest'; fi
  timeout 900 ./gradlew --no-daemon --max-workers=2 -I .lane-scratch/parallel.gradle :libp2p:test --tests "$selector" --rerun-tasks > ".lane-scratch/$phase.log" 2>&1
  rc=$?
  python3 .lane-scratch/capture.py "$phase" > ".lane-scratch/$phase-summary.txt"
  echo "$phase exit=$rc"
  head -1 ".lane-scratch/$phase-summary.txt"
  if [ "$rc" -gt 1 ]; then exit "$rc"; fi
 done
