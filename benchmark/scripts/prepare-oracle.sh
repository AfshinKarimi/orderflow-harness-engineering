#!/usr/bin/env bash
# Copies the hidden benchmark oracle into a work copy, so it can be evaluated with ./mvnw verify.
# Usage: prepare-oracle.sh <work-dir> <task-id, e.g. T03>
# Used by validate-oracle.sh and by the benchmark runner. Never run inside the agent's own directory.
set -euo pipefail

work=$1
task=$2
bench=$(cd "$(dirname "$0")/.." && pwd)
task_dir=$(ls -d "$bench"/oracle/"$task"-* 2>/dev/null || true)
[[ -d "$work/src/main/java/com/orderflow" ]] || { echo "not an OrderFlow work copy: $work" >&2; exit 2; }
[[ -n "$task_dir" ]] || { echo "no oracle for task $task" >&2; exit 2; }

target_java="$work/src/test/java/com/orderflow/benchmark/oracle"
rm -rf "$target_java" "$work/src/test/resources/benchmark"
mkdir -p "$target_java"
cp "$bench"/oracle/common/java/com/orderflow/benchmark/oracle/*.java "$target_java/"
cp "$task_dir"/java/com/orderflow/benchmark/oracle/*.java "$target_java/"
cp -R "$bench/oracle/common/resources/benchmark" "$work/src/test/resources/benchmark"

# Same ArchUnit configuration as the base commit (arm A does not have this file).
printf 'archRule.failOnEmptyShould=false\n' > "$work/src/test/resources/archunit.properties"

# Regression tests whose expectations the task deliberately changes are replaced (see the task's spec.md).
if [[ -d "$task_dir/replace" ]]; then
  cp -R "$task_dir/replace/." "$work/"
fi

# The architecture oracle needs ArchUnit; arm A removed it from pom.xml.
python3 -I - "$work/pom.xml" <<'PY'
import re
import sys
path = sys.argv[1]
pom = open(path).read()
if "archunit-junit5" not in pom:
    dependency = """        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <version>1.5.1</version>
            <scope>test</scope>
        </dependency>
    </dependencies>"""
    # Only the project-level list (exactly 4 spaces), never a plugin's dependencies.
    pom, count = re.subn(r"(?m)^    </dependencies>$", dependency, pom, count=1)
    if count != 1:
        sys.exit("pom.xml: project-level </dependencies> not found")
    open(path, "w").write(pom)
PY
echo "oracle for $task prepared in $work"
