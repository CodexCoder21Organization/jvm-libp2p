#!/usr/bin/env bash
set -euo pipefail
worktree=$1
log=$2
xml_dir=$3
classes_file=$4
r93t2_script_dir="$(cd "$(dirname "$0")" && pwd)"
filters=()
while IFS= read -r class_name; do
  [[ -n "$class_name" ]] && filters+=(--tests "$class_name")
done < "$classes_file"
mkdir -p "$xml_dir"
cd "$worktree"
exec "$r93t2_script_dir/slot-runner.sh" 2 "$log" env JAVA_HOME=/opt/java ./gradlew :libp2p:test --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process --init-script "$r93t2_script_dir/control.gradle" "-Dr93t.xml=$xml_dir" "${filters[@]}"
