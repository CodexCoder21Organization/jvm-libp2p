#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
evidence=evidence/evidence/R93T5
for item in window ordering termination aggregation; do
  git -C jvm-libp2p fetch origin > "$evidence/$item-rebase.log" 2>&1
  git -C jvm-libp2p rebase origin/develop >> "$evidence/$item-rebase.log" 2>&1
  python3 "$evidence/run.py" compile "$item-control" "controls/$item/NettyChannelClose.kt"
done
set +e
python3 "$evidence/run.py" run window-reverted window-control R93A4LateOfferDeliveryTest
python3 "$evidence/run.py" run ordering-reverted ordering-control R93A3HeldCloseOrderingTest R93A3ReentrantHeldCloseOrderingTest
python3 "$evidence/run.py" run termination-reverted termination-control F93mQuicDestroyedLoopReleaseTest
python3 "$evidence/run.py" run aggregation-reverted aggregation-control F93mPublishedCloseFailureTest
