#!/usr/bin/env bash
# Proves that a task's hidden oracle is valid before any benchmark run:
#   1. on the unchanged base commit, every acceptance test of the task fails (guards pass);
#   2. with the reference solution applied, every test passes: regression suite, task oracle, architecture oracle.
# Usage: validate-oracle.sh <task-id, e.g. T03>
# Writes the per-test results to benchmark/oracle/validation/<task>-{base,reference}.txt.
set -euo pipefail

task=$1
base_commit=69c9d98
bench=$(cd "$(dirname "$0")/.." && pwd)
repo=$(cd "$bench/.." && pwd)
patch=$(ls "$bench"/reference/"$task"-*.patch)
out="$bench/oracle/validation"
mkdir -p "$out"
work=$(mktemp -d "${TMPDIR:-/tmp}/oracle-$task.XXXXXX")
trap 'rm -rf "$work"' EXIT

export JAVA_HOME=${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}
maven=(./mvnw -o -B -Dcheckstyle.skip=true -Djacoco.skip=true -Dmaven.test.failure.ignore=true)
reports=(target/surefire-reports target/failsafe-reports)

git clone --quiet --no-local "$repo" "$work/repo"
cd "$work/repo"
git checkout --quiet --detach "$base_commit"

echo "== $task phase 1: hidden tests on the base commit $base_commit"
"$bench/scripts/prepare-oracle.sh" . "$task"
"${maven[@]}" verify -Dtest="${task}*Test" -Dit.test="${task}*IT" \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.failIfNoSpecifiedTests=false > "$work/base.log" 2>&1 || true
base_ok=0
python3 -I "$bench/scripts/oracle_check.py" base "$task" "${reports[@]}" | tee "$out/$task-base.txt" || base_ok=$?

echo "== $task phase 2: reference solution, full suite"
git checkout --quiet -- . && git clean -q -fd -e target
git apply --whitespace=nowarn "$patch"
"$bench/scripts/prepare-oracle.sh" . "$task"
rm -rf target/surefire-reports target/failsafe-reports
"${maven[@]}" clean verify > "$work/reference.log" 2>&1 || true
reference_ok=0
python3 -I "$bench/scripts/oracle_check.py" reference "$task" "${reports[@]}" | tee "$out/$task-reference.txt" \
  || reference_ok=$?

if [[ $base_ok -ne 0 || $reference_ok -ne 0 ]]; then
  echo "== $task oracle INVALID (logs: $work kept)"; trap - EXIT; exit 1
fi
echo "== $task oracle valid"
