"""Renders the benchmark evaluation report from recorded runs.

Usage:
  report.py <runs-dir> [--interpretation <file>] [--output <file>]

Reads runs/<run-id>/{metadata,metrics,review}.json (format: docs/benchmark/benchmark-design.md, section 3.5) and
writes the report described in docs/benchmark/report-template.md to stdout or --output.

Only recorded values are used. A missing value is printed as "not recorded" and left out of every aggregate;
each aggregate states its n. Nothing is estimated, defaulted or interpolated. A malformed record makes the run
invalid: it is listed with the reason and not counted. The interpretation is never generated: it is read
verbatim from --interpretation, and every statement in it must cite the fact IDs it relies on.
"""
import argparse
import json
import os
import re
import statistics
import sys

TASKS = [f"T{i:02d}" for i in range(1, 11)]
ARMS = {"A": "minimal instructions", "B": "full engineering harness"}
REPETITIONS = (1, 2, 3)
MAX_CORRECTIONS = 3
DECISIONS = {"merge": "accepted", "merge-after-fixes": "accepted-after-fixes", "reject": "rejected"}
OUTCOMES = ("accepted", "accepted-after-fixes", "rejected", "not-converged", "excluded")
SEVERITIES = ("must-fix", "should-fix", "nit")
REVIEW_ITEMS = tuple(f"R{i}" for i in range(1, 9))
NOT_RECORDED = "not recorded"
FACT_IDS = range(1, 15)


class InvalidRecord(Exception):
    pass


# ---------------------------------------------------------------- reading and validation

def _read_json(directory, name, required):
    path = os.path.join(directory, name)
    if not os.path.exists(path):
        if required:
            raise InvalidRecord(f"{name} missing")
        return None
    try:
        with open(path, encoding="utf-8") as file:
            data = json.load(file)
    except (OSError, ValueError) as error:
        raise InvalidRecord(f"{name} unreadable: {type(error).__name__}")
    if not isinstance(data, dict):
        raise InvalidRecord(f"{name} is not a JSON object")
    return data


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _get(data, key, kind, where, required=False):
    """Returns data[key] checked against kind; None if absent or null, unless required."""
    value = data.get(key)
    if value is None:
        if required:
            raise InvalidRecord(f"{where}.{key} missing")
        return None
    valid = {
        "int": _is_int(value) and value >= 0,
        "number": (_is_int(value) or isinstance(value, float)) and value >= 0,
        "str": isinstance(value, str) and value != "",
        "bool": isinstance(value, bool),
        "list": isinstance(value, list) and all(isinstance(item, str) for item in value),
        "dict": isinstance(value, dict),
    }[kind]
    if not valid:
        raise InvalidRecord(f"{where}.{key} is not a valid {kind}: {value!r}")
    return value


def _tests(data, key, where):
    counts = _get(data, key, "dict", where, required=True)
    passed = _get(counts, "passed", "int", f"{where}.{key}", required=True)
    total = _get(counts, "total", "int", f"{where}.{key}", required=True)
    if passed > total:
        raise InvalidRecord(f"{where}.{key}: passed {passed} > total {total}")
    return {"passed": passed, "total": total}


def _round(data, index):
    where = f"metrics.rounds[{index}]"
    if not isinstance(data, dict):
        raise InvalidRecord(f"{where} is not an object")
    if _get(data, "round", "int", where, required=True) != index:
        raise InvalidRecord(f"{where}.round must be {index}")
    result = {
        "architecture_violations": _get(data, "architecture_violations", "list", where, required=True),
        "regression": _tests(data, "regression_tests", where),
        "acceptance": _tests(data, "acceptance_tests", where),
    }
    if result["regression"]["total"] + result["acceptance"]["total"] == 0:
        raise InvalidRecord(f"{where}: no tests counted")
    return result


def _passes(oracle_round):
    return (not oracle_round["architecture_violations"]
            and all(oracle_round[k]["passed"] == oracle_round[k]["total"] for k in ("regression", "acceptance")))


def load_run(directory):
    """Returns the validated run as a dict, or raises InvalidRecord."""
    metadata = _read_json(directory, "metadata.json", required=True)
    run = {
        "run_id": _get(metadata, "run_id", "str", "metadata", required=True),
        "task": _get(metadata, "task", "str", "metadata", required=True),
        "arm": _get(metadata, "arm", "str", "metadata", required=True),
        "repetition": _get(metadata, "repetition", "int", "metadata", required=True),
        "excluded": _get(metadata, "excluded", "str", "metadata"),
    }
    if run["task"] not in TASKS:
        raise InvalidRecord(f"unknown task {run['task']!r}")
    if run["arm"] not in ARMS:
        raise InvalidRecord(f"unknown arm {run['arm']!r}")
    if run["repetition"] not in REPETITIONS:
        raise InvalidRecord(f"repetition {run['repetition']} not in {REPETITIONS}")
    for key, kind in (("model", "str"), ("claude_code_version", "str"), ("permission_mode", "str"),
                      ("max_turns", "int"), ("turn_timeout_minutes", "int"), ("base_sha", "str"),
                      ("seed", "int"), ("started_at", "str"), ("ended_at", "str")):
        run[key] = _get(metadata, key, kind, "metadata")
    if run["excluded"]:
        return run

    metrics = _read_json(directory, "metrics.json", required=False)
    run["rounds"] = None
    if metrics is not None:
        rounds = metrics.get("rounds")
        if not isinstance(rounds, list) or not rounds:
            raise InvalidRecord("metrics.rounds missing or empty")
        if len(rounds) > MAX_CORRECTIONS + 1:
            raise InvalidRecord(f"{len(rounds) - 1} correction rounds, at most {MAX_CORRECTIONS} allowed")
        run["rounds"] = [_round(data, index) for index, data in enumerate(rounds)]
        if any(_passes(r) for r in run["rounds"][:-1]):
            raise InvalidRecord("a correction round followed a round in which the oracle passed")
        if not _passes(run["rounds"][-1]) and len(rounds) - 1 < MAX_CORRECTIONS:
            raise InvalidRecord(f"oracle still failing after {len(rounds) - 1} correction rounds, "
                                f"protocol requires {MAX_CORRECTIONS}")
        changes = _get(metrics, "code_changes", "dict", "metrics")
        run["code_changes"] = None if changes is None else {
            key: _get(changes, key, kind, "metrics.code_changes")
            for key, kind in (("files_changed", "int"), ("lines_added", "int"), ("lines_removed", "int"),
                              ("out_of_scope_files", "list"), ("new_dependencies", "list"),
                              ("edited_migrations", "list"), ("weakened_tests", "list"))}
        run["false_success_claim"] = _get(metrics, "false_success_claim", "bool", "metrics")
        cost = _get(metrics, "cost", "dict", "metrics")
        run["cost"] = None if cost is None else {
            key: _get(cost, key, kind, "metrics.cost")
            for key, kind in (("input_tokens", "int"), ("output_tokens", "int"), ("wall_clock_seconds", "number"),
                              ("tool_calls", "int"))}

    review = _read_json(directory, "review.json", required=False)
    run["review"] = None
    if review is not None:
        decision = _get(review, "decision", "str", "review", required=True)
        if decision not in DECISIONS:
            raise InvalidRecord(f"unknown review decision {decision!r}")
        findings = review.get("findings")
        if not isinstance(findings, list):
            raise InvalidRecord("review.findings missing")
        for index, finding in enumerate(findings):
            if not isinstance(finding, dict) or finding.get("item") not in REVIEW_ITEMS \
                    or finding.get("severity") not in SEVERITIES:
                raise InvalidRecord(f"review.findings[{index}] needs item R1-R8 and severity {SEVERITIES}")
        run["review"] = {
            "reviewer": _get(review, "reviewer", "str", "review"),
            "minutes": _get(review, "minutes", "number", "review", required=True),
            "decision": decision,
            "findings": findings,
            "confirmed_architecture_violations":
                _get(review, "confirmed_architecture_violations", "list", "review", required=True),
        }
    return run


def load_runs(runs_dir):
    """Returns (valid runs, invalid runs as (directory name, reason))."""
    valid, invalid = [], []
    names = sorted(n for n in os.listdir(runs_dir) if os.path.isdir(os.path.join(runs_dir, n))) \
        if os.path.isdir(runs_dir) else []
    for name in names:
        try:
            valid.append(load_run(os.path.join(runs_dir, name)))
        except InvalidRecord as error:
            invalid.append((name, str(error)))
    slots = {}
    for run in valid:
        slots.setdefault((run["task"], run["arm"], run["repetition"]), []).append(run)
    duplicates = {id(run) for runs in slots.values() if len(runs) > 1 for run in runs}
    for run in valid:
        if id(run) in duplicates:
            invalid.append((run["run_id"], f"duplicate record for {run['task']}/{run['arm']}/{run['repetition']}"))
    valid = [run for run in valid if id(run) not in duplicates]
    valid.sort(key=lambda r: (r["task"], r["arm"], r["repetition"]))
    return valid, sorted(invalid)


# ---------------------------------------------------------------- derived values (None = not recorded)

def evaluated(run):
    return not run["excluded"] and run.get("rounds") is not None


def corrections(run):
    """Correction rounds until the oracle passed, or 'not-converged'."""
    if not evaluated(run):
        return None
    return len(run["rounds"]) - 1 if _passes(run["rounds"][-1]) else "not-converged"


def architecture_violations(run, final):
    if not evaluated(run):
        return None
    if not final:
        return len(run["rounds"][0]["architecture_violations"])
    if run["review"] is None:
        return None
    return len(run["rounds"][-1]["architecture_violations"]) + len(run["review"]["confirmed_architecture_violations"])


def pass_rate(run, final):
    if not evaluated(run):
        return None
    oracle_round = run["rounds"][-1 if final else 0]
    passed = oracle_round["regression"]["passed"] + oracle_round["acceptance"]["passed"]
    total = oracle_round["regression"]["total"] + oracle_round["acceptance"]["total"]
    return passed / total


def outcome(run):
    if run["excluded"]:
        return "excluded"
    if corrections(run) == "not-converged":
        return "not-converged"
    if not evaluated(run) or run["review"] is None:
        return None
    return DECISIONS[run["review"]["decision"]]


def review_minutes(run):
    return run["review"]["minutes"] if not run["excluded"] and run.get("review") else None


def severity_counts(run):
    if run["excluded"] or not run.get("review"):
        return None
    return {s: sum(1 for f in run["review"]["findings"] if f["severity"] == s) for s in SEVERITIES}


def change_flag(run, key):
    """True if the final diff has at least one entry under key, None if not recorded."""
    changes = None if run["excluded"] else run.get("code_changes")
    if changes is None or changes[key] is None:
        return None
    return bool(changes[key])


# ---------------------------------------------------------------- formatting

def _number(value):
    return str(int(value)) if float(value).is_integer() else f"{value:.2f}"


def _percent(value):
    return f"{value * 100:.1f} %"


def _value(value, formatter=str):
    return NOT_RECORDED if value is None else formatter(value)


def _n(values, planned):
    return f"n = {len(values)} of {planned}"


def median_mean(values, planned, formatter=_number):
    values = [v for v in values if v is not None]
    if not values:
        return f"{NOT_RECORDED} ({_n(values, planned)})"
    return (f"median {formatter(statistics.median(values))}, mean {formatter(statistics.mean(values))} "
            f"({_n(values, planned)})")


def count_of(flags, planned):
    flags = [f for f in flags if f is not None]
    if not flags:
        return f"{NOT_RECORDED} ({_n(flags, planned)})"
    return f"{sum(flags)} runs ({_n(flags, planned)})"


def distribution(values, keys, planned):
    values = [v for v in values if v is not None]
    if not values:
        return f"{NOT_RECORDED} ({_n(values, planned)})"
    return ", ".join(f"{k}: {values.count(k)}" for k in keys) + f" ({_n(values, planned)})"


def _table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(str(cell) for cell in row) + " |" for row in rows]
    return lines


# ---------------------------------------------------------------- report sections

def completeness(valid, invalid):
    planned = len(TASKS) * len(REPETITIONS)
    lines = ["## 1. Completeness", ""]
    rows = []
    for arm in ARMS:
        runs = [r for r in valid if r["arm"] == arm]
        rows.append([f"{arm} ({ARMS[arm]})", planned, len(runs),
                     sum(1 for r in runs if r["excluded"]),
                     sum(1 for r in runs if not r["excluded"] and not evaluated(r)),
                     sum(1 for r in runs if evaluated(r) and r["review"] is None)])
    lines += _table(["Arm", "Planned", "Recorded", "Excluded", "Awaiting evaluation", "Awaiting review"], rows)
    total = len(valid)
    lines += ["", f"{total} of {planned * len(ARMS)} runs recorded; {len(invalid)} invalid records (not counted)."]
    recorded = {(r["task"], r["arm"], r["repetition"]) for r in valid}
    missing = [f"{t}: " + " ".join(f"{a}{p}" for a in ARMS for p in REPETITIONS if (t, a, p) not in recorded)
               for t in TASKS if any((t, a, p) not in recorded for a in ARMS for p in REPETITIONS)]
    if missing:
        lines += ["", "Not recorded (task: arm and repetition):", ""] + [f"- {m}" for m in missing]
    excluded = [r for r in valid if r["excluded"]]
    if excluded:
        lines += ["", "Excluded:", ""] + [f"- `{r['run_id']}` ({r['task']}/{r['arm']}/{r['repetition']}): "
                                          f"{r['excluded']}" for r in excluded]
    if invalid:
        lines += ["", "Invalid records:", ""] + [f"- `{name}`: {reason}" for name, reason in invalid]
    return lines


def facts(valid):
    lines = ["## 2. Measured facts", ""]
    counted = [r for r in valid if not r["excluded"]]
    if not any(evaluated(r) for r in counted):
        return lines + ["No evaluated runs recorded: there are no measured facts."]
    lines += ["Computed by `benchmark/scripts/report.py` from the run records only. Excluded and invalid runs are",
              "not counted. Each value states n, the number of runs it is based on, out of the 30 planned per arm.",
              "", "### Per arm", ""]
    planned = len(TASKS) * len(REPETITIONS)
    by_arm = {arm: [r for r in counted if r["arm"] == arm] for arm in ARMS}

    def row(fact_id, name, compute):
        return [f"F{fact_id}", name] + [compute(by_arm[arm]) for arm in ARMS]

    def severities(runs):
        counts = [c for c in map(severity_counts, runs) if c is not None]
        if not counts:
            return f"{NOT_RECORDED} ({_n(counts, planned)})"
        return ", ".join(f"{s}: {sum(c[s] for c in counts)}" for s in SEVERITIES) + f" ({_n(counts, planned)})"

    def cost(runs, key, formatter=_number):
        return median_mean([(r.get("cost") or {}).get(key) for r in runs], planned, formatter)

    rows = [
        row(1, "Architecture violations, first done (hidden rules)",
            lambda runs: median_mean([architecture_violations(r, False) for r in runs], planned)),
        row(2, "Architecture violations, final (hidden rules + reviewer-confirmed)",
            lambda runs: median_mean([architecture_violations(r, True) for r in runs], planned)),
        row(3, "Test pass rate, first done", lambda runs: median_mean([pass_rate(r, False) for r in runs], planned,
                                                                       _percent)),
        row(4, "Test pass rate, final", lambda runs: median_mean([pass_rate(r, True) for r in runs], planned,
                                                                  _percent)),
        row(5, "Correction iterations", lambda runs: distribution([corrections(r) for r in runs],
                                                                   [0, 1, 2, 3, "not-converged"], planned)),
        row(6, "Human review time (minutes)", lambda runs: median_mean([review_minutes(r) for r in runs], planned)),
        row(7, "Review findings (total)", severities),
        row(8, "Final outcome", lambda runs: distribution([outcome(r) for r in runs], OUTCOMES[:4], planned)),
        row(9, "False success claims", lambda runs: count_of([r.get("false_success_claim") for r in runs],
                                                              planned)),
        row(10, "Runs with weakened tests", lambda runs: count_of([change_flag(r, "weakened_tests") for r in runs],
                                                                   planned)),
        row(11, "Runs with edited migrations",
            lambda runs: count_of([change_flag(r, "edited_migrations") for r in runs], planned)),
        row(12, "Runs with out-of-scope files or new dependencies",
            lambda runs: count_of([None if change_flag(r, "out_of_scope_files") is None
                                   else change_flag(r, "out_of_scope_files") or change_flag(r, "new_dependencies")
                                   for r in runs], planned)),
        row(13, "Cost: input tokens / output tokens / wall clock (minutes)",
            lambda runs: " / ".join([cost(runs, "input_tokens"), cost(runs, "output_tokens"),
                                     median_mean([None if (r.get("cost") or {}).get("wall_clock_seconds") is None
                                                  else r["cost"]["wall_clock_seconds"] / 60 for r in runs],
                                                 planned)])),
    ]
    lines += _table(["ID", "Fact"] + [f"Arm {arm}" for arm in ARMS], rows)
    lines += ["", "### F14 Per task", "",
              "Means over the recorded repetitions; correction iterations and outcomes per repetition (1, 2, 3; "
              "`-` = not recorded).", ""]
    task_rows = []
    for task in TASKS:
        for arm in ARMS:
            runs = [r for r in counted if r["task"] == task and r["arm"] == arm]
            per_rep = {r["repetition"]: r for r in runs}

            def mean(values, formatter=_number):
                values = [v for v in values if v is not None]
                return f"{formatter(statistics.mean(values))} (n = {len(values)})" if values else NOT_RECORDED

            def each(compute):
                return ", ".join(_value(compute(per_rep[p]) if p in per_rep else None).replace(NOT_RECORDED, "-")
                                 for p in REPETITIONS)

            task_rows.append([task, arm, mean([architecture_violations(r, False) for r in runs]),
                              mean([pass_rate(r, False) for r in runs], _percent), each(corrections),
                              mean([review_minutes(r) for r in runs]), each(outcome)])
    lines += _table(["Task", "Arm", "Arch. violations, first done", "Pass rate, first done", "Corrections",
                     "Review minutes", "Outcome"], task_rows)
    lines += ["", "### Not computed", "",
              "The hypothesis tests H1-H4 (Wilcoxon signed-rank test and bootstrap confidence intervals, design "
              "section 5)", "are not computed by this script."]
    return lines


def check_interpretation(text):
    """Returns a list of problems: uncited statements and unknown fact IDs."""
    problems, blocks, current = [], [], []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            if current:
                blocks.append(" ".join(current))
            current = []
            continue
        if re.match(r"([-*]|\d+\.)\s", stripped) and current:
            blocks.append(" ".join(current))
            current = []
        current.append(stripped)
    if current:
        blocks.append(" ".join(current))
    for block in blocks:
        ids = [int(i) for i in re.findall(r"\bF(\d+)\b", block)]
        if not ids:
            problems.append(f"no fact cited: {block[:70]}")
        problems += [f"unknown fact F{i}: {block[:70]}" for i in ids if i not in FACT_IDS]
    return problems


def interpretation(text):
    lines = ["## 3. Interpretation", "",
             "_Human-written, not generated. Each statement cites the measured facts (F1-F14) it relies on._", ""]
    if text is None:
        return lines + ["Not written."]
    return lines + text.rstrip("\n").splitlines()


def run_record(run):
    lines = [f"### {run['task']} / arm {run['arm']} / repetition {run['repetition']} (`{run['run_id']}`)", ""]
    if run["excluded"]:
        return lines + [f"Excluded: {run['excluded']}"]
    rounds, review, changes = run.get("rounds"), run.get("review"), run.get("code_changes")

    def tests(index):
        if rounds is None:
            return NOT_RECORDED
        r = rounds[index]
        return (f"{_percent(pass_rate(run, index == -1))}: regression {r['regression']['passed']}/"
                f"{r['regression']['total']}, task acceptance {r['acceptance']['passed']}/{r['acceptance']['total']}")

    def rules(index):
        return NOT_RECORDED if rounds is None else ", ".join(rounds[index]["architecture_violations"]) or "none"

    def count(index):
        return len(rounds[index]["architecture_violations"])

    def listed(key):
        return NOT_RECORDED if changes is None or changes[key] is None else ", ".join(changes[key]) or "none"

    model = (f"{_value(run['model'])}; Claude Code {_value(run['claude_code_version'])}; permission mode "
             f"{_value(run['permission_mode'])}; max. turns {_value(run['max_turns'])}; turn timeout "
             f"{_value(run['turn_timeout_minutes'])} min")
    code = NOT_RECORDED if changes is None else (
        f"{_value(changes['files_changed'])} files, +{_value(changes['lines_added'])} "
        f"-{_value(changes['lines_removed'])} lines; out of scope: {listed('out_of_scope_files')}; new "
        f"dependencies: {listed('new_dependencies')}; edited migrations: {listed('edited_migrations')}; weakened "
        f"tests: {listed('weakened_tests')}")
    if review is None:
        review_text = NOT_RECORDED
    else:
        counts = severity_counts(run)
        items = sorted({f["item"] for f in review["findings"]})
        review_text = (f"{_number(review['minutes'])} min, decision {review['decision']}; findings "
                       + ", ".join(f"{s}: {counts[s]}" for s in SEVERITIES)
                       + (f" ({', '.join(items)})" if items else ""))
    confirmed = NOT_RECORDED if review is None else (", ".join(review["confirmed_architecture_violations"]) or "none")
    rows = [
        ["Task ID", run["task"]],
        ["Model and configuration", model],
        ["Harness configuration", f"arm {run['arm']} ({ARMS[run['arm']]}), base {_value(run['base_sha'])}"],
        ["Code changes (final diff)", code],
        ["Architecture tests, first done", rules(0) if rounds is None else f"{count(0)} failed: {rules(0)}"],
        ["Architecture tests, final", rules(-1) if rounds is None
            else f"{count(-1)} failed: {rules(-1)}; reviewer-confirmed: {confirmed}"],
        ["Functional tests, first done", tests(0)],
        ["Functional tests, final", tests(-1)],
        ["Correction attempts", _value(corrections(run))],
        ["Human review", review_text],
        ["Final outcome", _value(outcome(run))],
    ]
    return lines + _table(["Field", "Recorded value"], rows)


def render(runs_dir, interpretation_text=None):
    """Returns the report; raises ValueError if the interpretation has uncited statements or unknown fact IDs."""
    if interpretation_text is not None:
        problems = check_interpretation(interpretation_text)
        if problems:
            raise ValueError("Interpretation rejected:\n" + "\n".join(f"- {p}" for p in problems))
    valid, invalid = load_runs(runs_dir)
    lines = ["# Harness benchmark: evaluation report", "",
             "Generated by `benchmark/scripts/report.py` from the run records; template and rules: "
             "`docs/benchmark/report-template.md`.", "Sections 1, 2 and the appendix are measured facts. "
             "Section 3 is interpretation, written by a person.", ""]
    lines += completeness(valid, invalid) + [""] + facts(valid) + [""] + interpretation(interpretation_text)
    lines += ["", "## Appendix: run records", ""]
    if not valid:
        lines += ["No runs recorded."]
    for run in valid:
        lines += run_record(run) + [""]
    return "\n".join(lines).rstrip("\n") + "\n"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("runs_dir")
    parser.add_argument("--interpretation")
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    text = None
    if args.interpretation:
        with open(args.interpretation, encoding="utf-8") as file:
            text = file.read()
    try:
        report = render(args.runs_dir, text)
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    if args.output:
        with open(args.output, "w", encoding="utf-8") as file:
            file.write(report)
    else:
        sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
