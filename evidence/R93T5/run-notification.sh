#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
evidence=evidence/evidence/R93T5
git -C jvm-libp2p fetch origin > "$evidence/notification-rebase.log" 2>&1
git -C jvm-libp2p rebase origin/develop >> "$evidence/notification-rebase.log" 2>&1
python3 "$evidence/run.py" compile notification-test R93T5TerminationNotificationTest.kt
git -C jvm-libp2p fetch origin >> "$evidence/notification-rebase.log" 2>&1
git -C jvm-libp2p rebase origin/develop >> "$evidence/notification-rebase.log" 2>&1
python3 "$evidence/run.py" compile notification-control R93T5TerminationNotificationTest.kt controls/notification/NettyChannelClose.kt
python3 "$evidence/run.py" run notification-head notification-test R93T5TerminationNotificationTest
set +e
python3 "$evidence/run.py" run notification-reverted notification-control R93T5TerminationNotificationTest
