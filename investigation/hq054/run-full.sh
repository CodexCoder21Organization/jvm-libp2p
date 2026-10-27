#!/bin/bash
set -eu
cd /home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol
head_sha=$(git rev-parse HEAD)
printf 'HEAD=%s START=%s\n' "$head_sha" "$(date -u +%FT%TZ)"
exec flock -E 75 -w 2100 /home/u/lanes/hd27/kompile-full-suite.lock timeout --kill-after=15s 3600 scripts/test.bash --local-and-remote --test . --log /home/u/lanes/hd27/out/hq054-full.xml
