#!/usr/bin/env bash
#
# OrderFlow quality gate:
#   compile -> style (Checkstyle) -> unit tests -> architecture tests -> integration tests -> coverage (JaCoCo).
# Unit and integration stages run with the JaCoCo agent and append to one target/jacoco.exec.
# Stops at the first failing stage and exits non-zero. Works locally and in CI.
# If GITHUB_STEP_SUMMARY is set (GitHub Actions), a per-stage result table is appended to it.
#
# Requirements: JDK 21 (auto-selected on macOS if installed), Docker for integration tests.
#
set -euo pipefail

cd "$(dirname "$0")/.."

MVN=(./mvnw -B -ntp)
STAGE_RESULTS=()
SUMMARY_ROWS=()

log() { printf '\n==> %s\n' "$*"; }

# abort <message> [exit code]: reports the failure on the console and in the CI summary, then exits.
abort() {
    printf '\nQUALITY GATE FAILED: %s\n' "$1" >&2
    write_summary "❌ FAILED: $1"
    exit "${2:-1}"
}

write_summary() {
    [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
    {
        printf '## Quality gate: %s\n\n| Stage | Result | Time | Tests |\n|---|---|---|---|\n' "$1"
        if [[ ${#SUMMARY_ROWS[@]} -gt 0 ]]; then
            printf '%s\n' "${SUMMARY_ROWS[@]}"
        fi
        printf '\nReports (test results, coverage, Checkstyle) are attached to the run as the `quality-reports` artifact.\n'
    } >> "$GITHUB_STEP_SUMMARY"
}

# stage_tests <stage>: "N run, F failed, S skipped" from the JUnit XML reports of that stage, or "–".
stage_tests() {
    local files=() f
    shopt -s nullglob
    case "$1" in
        unit)
            for f in target/surefire-reports/TEST-*.xml; do
                [[ "$f" == *ArchitectureTest.xml ]] || files+=("$f")
            done ;;
        architecture) files=(target/surefire-reports/TEST-*ArchitectureTest.xml) ;;
        integration) files=(target/failsafe-reports/TEST-*.xml) ;;
    esac
    shopt -u nullglob
    if [[ ${#files[@]} -eq 0 ]]; then
        echo "–"
        return
    fi
    awk '
        function attr(s, n) { return match(s, n "=\"[0-9]+\"") ? substr(s, RSTART + length(n) + 2, RLENGTH - length(n) - 3) + 0 : 0 }
        !seen[FILENAME] && /<testsuite / {
            seen[FILENAME] = 1
            run += attr($0, "tests"); failed += attr($0, "failures") + attr($0, "errors"); skipped += attr($0, "skipped")
        }
        END { printf "%d run, %d failed, %d skipped", run, failed, skipped }
    ' "${files[@]}"
}

java_major() {
    "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

ensure_jdk21() {
    if [[ "$(java_major)" == "21" ]]; then
        return
    fi
    if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
        JAVA_HOME="$(/usr/libexec/java_home -v 21)"
        export JAVA_HOME
        log "Selected JDK 21: $JAVA_HOME"
        return
    fi
    abort "JDK 21 required, found '$(java_major)'. Set JAVA_HOME to a JDK 21 installation."
}

ensure_docker() {
    if ! docker info >/dev/null 2>&1; then
        abort "Docker is not reachable; integration tests need it (Testcontainers). Start Docker and retry."
    fi
}

# run_stage <name> <command...>: runs the command, records its result and duration, aborts on failure.
run_stage() {
    local name="$1"
    shift
    log "[$name] $*"
    local start=$SECONDS code=0
    "$@" || code=$?
    local seconds=$((SECONDS - start))
    if [[ $code -ne 0 ]]; then
        SUMMARY_ROWS+=("| $name | ❌ failed (exit $code) | ${seconds}s | $(stage_tests "$name") |")
        abort "stage '$name' (exit code $code)" "$code"
    fi
    STAGE_RESULTS+=("$(printf '%-14s OK  %4ss' "$name" "$seconds")")
    SUMMARY_ROWS+=("| $name | ✅ passed | ${seconds}s | $(stage_tests "$name") |")
}

ensure_jdk21
run_stage "compile"      "${MVN[@]}" clean test-compile
run_stage "style"        "${MVN[@]}" checkstyle:check
run_stage "unit"         "${MVN[@]}" jacoco:prepare-agent surefire:test -Dtest='!ArchitectureTest,!*IT'
run_stage "architecture" "${MVN[@]}" surefire:test -Dtest=ArchitectureTest
ensure_docker
run_stage "integration"  "${MVN[@]}" jacoco:prepare-agent failsafe:integration-test failsafe:verify
run_stage "coverage"     "${MVN[@]}" jacoco:report jacoco:check

log "QUALITY GATE PASSED"
printf '    %s\n' "${STAGE_RESULTS[@]}"
write_summary "✅ PASSED"
