"""Summarises JUnit XML reports of an oracle run and checks the validation expectations.

Usage:
  oracle_check.py base      <task> <reports-dir>...   every non-guard test of the task must fail
  oracle_check.py reference <task> <reports-dir>...   every test must pass, task tests and oracle must have run
Prints one line per test case and exits 1 if the expectation is not met.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ORACLE = "com.orderflow.benchmark.oracle."


def cases(report_dirs):
    for directory in report_dirs:
        for path in sorted(glob.glob(os.path.join(directory, "TEST-*.xml"))):
            # The report file carries the fully qualified class; some engines (ArchUnit) write only the simple
            # name into each test case.
            report_class = os.path.basename(path)[len("TEST-"):-len(".xml")]
            for case in ET.parse(path).getroot().iter("testcase"):
                if case.find("skipped") is not None:
                    status = "SKIPPED"
                elif case.find("failure") is not None:
                    status = "FAILED"
                elif case.find("error") is not None:
                    status = "ERROR"
                else:
                    status = "PASSED"
                yield report_class, case.get("name"), status


def main():
    phase, task, dirs = sys.argv[1], sys.argv[2], sys.argv[3:]
    results = list(cases(dirs))
    task_prefix = ORACLE + task
    problems = []
    for cls, name, status in results:
        is_task = cls.startswith(task_prefix)
        is_guard = cls.endswith("GuardIT") or cls.endswith("GuardTest")
        if phase == "base":
            if not is_task:
                continue
            expected = "PASSED" if is_guard else "FAILED/ERROR"
            ok = status == "PASSED" if is_guard else status in ("FAILED", "ERROR")
        else:
            expected = "PASSED"
            ok = status == "PASSED"
        print(f"{'ok  ' if ok else 'BAD '} {status:7} (expected {expected:12}) {cls.removeprefix(ORACLE)}.{name}")
        if not ok:
            problems.append(f"{cls}.{name}")
    task_cases = [r for r in results if r[0].startswith(task_prefix)]
    if not task_cases:
        problems.append(f"no oracle tests of {task} ran")
    if phase == "reference" and not any(r[0] == ORACLE + "ArchitectureOracleTest" for r in results):
        problems.append("ArchitectureOracleTest did not run")
    print(f"\n{phase}: {len(task_cases)} task oracle tests, {len(results)} test cases in total, "
          f"{len(problems)} problem(s)")
    for problem in problems:
        print(f"  - {problem}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
