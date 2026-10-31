"""Unit tests for report.py. Run: python3 -m unittest discover -s benchmark/scripts -p 'test_*.py'

All run records below are FIXTURES invented to exercise the script. They are not benchmark results.
"""
import json
import os
import tempfile
import unittest

import report


def oracle_round(index, violations=(), regression=(120, 120), acceptance=(6, 6)):
    return {"round": index, "architecture_violations": list(violations),
            "regression_tests": {"passed": regression[0], "total": regression[1]},
            "acceptance_tests": {"passed": acceptance[0], "total": acceptance[1]}}


FAILING = {"violations": ["rule_9_modules_only_via_port_in"], "acceptance": (2, 6)}


class ReportTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.runs = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def record(self, run_id, task="T01", arm="A", repetition=1, rounds=None, review=None, metadata=None,
               metrics=None):
        directory = os.path.join(self.runs, run_id)
        os.makedirs(directory)
        files = {"metadata.json": {"run_id": run_id, "task": task, "arm": arm, "repetition": repetition,
                                   "model": "fixture-model", **(metadata or {})}}
        if rounds is not None:
            files["metrics.json"] = {"rounds": rounds, **(metrics or {})}
        if review is not None:
            files["review.json"] = {"minutes": 10, "decision": "merge", "findings": [],
                                    "confirmed_architecture_violations": [], **review}
        for name, content in files.items():
            with open(os.path.join(directory, name), "w", encoding="utf-8") as file:
                json.dump(content, file)

    def facts_row(self, text, fact_id):
        return next(line for line in text.splitlines() if line.startswith(f"| F{fact_id} |"))

    def test_without_runs_reports_no_numbers(self):
        text = report.render(self.runs)
        self.assertIn("0 of 60 runs recorded", text)
        self.assertIn("No evaluated runs recorded: there are no measured facts.", text)
        self.assertNotIn("| F1 |", text)
        self.assertIn("T10: A1 A2 A3 B1 B2 B3", text)

    def test_run_passing_on_first_done_is_accepted_without_corrections(self):
        self.record("r1", rounds=[oracle_round(0)], review={"decision": "merge", "minutes": 12})
        text = report.render(self.runs)
        self.assertIn("| Correction attempts | 0 |", text)
        self.assertIn("| Final outcome | accepted |", text)
        self.assertIn("| Functional tests, first done | 100.0 %: regression 120/120, task acceptance 6/6 |", text)
        self.assertIn("| Human review | 12 min, decision merge; findings must-fix: 0, should-fix: 0, nit: 0 |", text)

    def test_corrections_count_rounds_until_the_oracle_passes(self):
        self.record("r1", rounds=[oracle_round(0, **FAILING), oracle_round(1, **FAILING), oracle_round(2)])
        self.assertIn("| Correction attempts | 2 |", report.render(self.runs))

    def test_failing_after_three_corrections_is_not_converged_regardless_of_review(self):
        self.record("r1", rounds=[oracle_round(i, **FAILING) for i in range(4)], review={"decision": "merge"})
        text = report.render(self.runs)
        self.assertIn("| Final outcome | not-converged |", text)
        self.assertIn("not-converged: 1", self.facts_row(text, 5))

    def test_record_breaking_the_correction_protocol_is_invalid_and_not_counted(self):
        self.record("stopped-early", rounds=[oracle_round(0, **FAILING), oracle_round(1, **FAILING)])
        self.record("too-many", task="T02", rounds=[oracle_round(i, **FAILING) for i in range(5)])
        self.record("continued", task="T03", rounds=[oracle_round(0), oracle_round(1)])
        text = report.render(self.runs)
        self.assertIn("- `stopped-early`: oracle still failing after 1 correction rounds, protocol requires 3", text)
        self.assertIn("- `too-many`: 4 correction rounds, at most 3 allowed", text)
        self.assertIn("- `continued`: a correction round followed a round in which the oracle passed", text)
        self.assertIn("0 of 60 runs recorded; 3 invalid records", text)

    def test_malformed_records_are_invalid_with_reason(self):
        self.record("bad-arm", arm="C")
        self.record("bad-task", task="T11")
        self.record("bad-decision", rounds=[oracle_round(0)], review={"decision": "maybe"})
        self.record("bad-count", rounds=[oracle_round(0, acceptance=(7, 6))])
        self.record("no-tests", rounds=[oracle_round(0, regression=(0, 0), acceptance=(0, 0))])
        self.record("bad-finding", rounds=[oracle_round(0)], review={"findings": [{"item": "R9", "severity": "nit"}]})
        os.makedirs(os.path.join(self.runs, "no-metadata"))
        text = report.render(self.runs)
        self.assertIn("- `bad-arm`: unknown arm 'C'", text)
        self.assertIn("- `bad-task`: unknown task 'T11'", text)
        self.assertIn("- `bad-decision`: unknown review decision 'maybe'", text)
        self.assertIn("- `bad-count`: metrics.rounds[0].acceptance_tests: passed 7 > total 6", text)
        self.assertIn("- `no-tests`: metrics.rounds[0]: no tests counted", text)
        self.assertIn("- `bad-finding`: review.findings[0] needs item R1-R8", text)
        self.assertIn("- `no-metadata`: metadata.json missing", text)
        self.assertIn("No evaluated runs recorded", text)

    def test_duplicate_slot_invalidates_both_records(self):
        self.record("r1", rounds=[oracle_round(0)])
        self.record("r2", rounds=[oracle_round(0)])
        text = report.render(self.runs)
        self.assertIn("- `r1`: duplicate record for T01/A/1", text)
        self.assertIn("- `r2`: duplicate record for T01/A/1", text)

    def test_missing_values_are_not_recorded_and_left_out_of_aggregates(self):
        self.record("reviewed", rounds=[oracle_round(0)], review={"minutes": 30})
        self.record("unreviewed", repetition=2, rounds=[oracle_round(0)])
        text = report.render(self.runs)
        self.assertIn("median 30, mean 30 (n = 1 of 30)", self.facts_row(text, 6))
        self.assertIn("not recorded (n = 0 of 30)", self.facts_row(text, 6))  # arm B
        self.assertIn("not recorded (n = 0 of 30)", self.facts_row(text, 9))
        self.assertIn("| Human review | not recorded |", text)
        self.assertIn("| Code changes (final diff) | not recorded |", text)
        self.assertIn("| Final outcome | not recorded |", text)
        self.assertIn("| Architecture tests, final | 0 failed: none; reviewer-confirmed: not recorded |", text)
        self.assertRegex(text, r"\| A \(minimal instructions\) \| 30 \| 2 \| 0 \| 0 \| 1 \|")

    def test_aggregates_are_computed_from_recorded_values(self):
        self.record("a1", rounds=[oracle_round(0, violations=["x", "y"], acceptance=(0, 6))]
                    + [oracle_round(i, **FAILING) for i in range(1, 4)],
                    review={"minutes": 10, "confirmed_architecture_violations": ["cross-schema join"]})
        self.record("a2", repetition=2, rounds=[oracle_round(0, acceptance=(3, 6), regression=(117, 120)),
                                                oracle_round(1)],
                    review={"minutes": 25, "decision": "merge-after-fixes",
                            "findings": [{"item": "R5", "severity": "should-fix"},
                                         {"item": "R1", "severity": "must-fix"}]})
        self.record("b1", arm="B", rounds=[oracle_round(0)], review={"minutes": 5})
        text = report.render(self.runs)
        self.assertIn("| F1 | Architecture violations, first done (hidden rules) | median 1, mean 1 (n = 2 of 30) | "
                      "median 0, mean 0 (n = 1 of 30) |", text)
        # final: a1 = 1 hidden rule + 1 reviewer-confirmed, a2 = 0
        self.assertIn("median 1, mean 1 (n = 2 of 30)", self.facts_row(text, 2))
        # first done: a1 120/126, a2 120/126
        self.assertIn("median 95.2 %, mean 95.2 % (n = 2 of 30)", self.facts_row(text, 3))
        self.assertIn("0: 0, 1: 1, 2: 0, 3: 0, not-converged: 1 (n = 2 of 30)", self.facts_row(text, 5))
        self.assertIn("median 17.50, mean 17.50 (n = 2 of 30)", self.facts_row(text, 6))
        self.assertIn("must-fix: 1, should-fix: 1, nit: 0 (n = 2 of 30)", self.facts_row(text, 7))
        self.assertIn("accepted: 0, accepted-after-fixes: 1, rejected: 0, not-converged: 1", self.facts_row(text, 8))
        self.assertIn("| T01 | A | 1 (n = 2) | 95.2 % (n = 2) | not-converged, 1, - | 17.50 (n = 2) | "
                      "not-converged, accepted-after-fixes, - |", text)
        self.assertIn("| T02 | A | not recorded | not recorded | -, -, - |", text)

    def test_code_change_flags_and_secondary_metrics(self):
        self.record("r1", rounds=[oracle_round(0)], metrics={
            "false_success_claim": True,
            "code_changes": {"files_changed": 4, "lines_added": 80, "lines_removed": 3,
                             "out_of_scope_files": [], "new_dependencies": ["org.projectlombok:lombok"],
                             "edited_migrations": ["V2__create_orders.sql"], "weakened_tests": []},
            "cost": {"input_tokens": 1000, "output_tokens": 200, "wall_clock_seconds": 600, "tool_calls": 40}})
        text = report.render(self.runs)
        self.assertIn("1 runs (n = 1 of 30)", self.facts_row(text, 9))
        self.assertIn("0 runs (n = 1 of 30)", self.facts_row(text, 10))
        self.assertIn("1 runs (n = 1 of 30)", self.facts_row(text, 11))
        self.assertIn("1 runs (n = 1 of 30)", self.facts_row(text, 12))
        self.assertIn("median 10, mean 10 (n = 1 of 30)", self.facts_row(text, 13))
        self.assertIn("4 files, +80 -3 lines; out of scope: none; new dependencies: org.projectlombok:lombok; "
                      "edited migrations: V2__create_orders.sql; weakened tests: none", text)

    def test_excluded_run_is_listed_but_not_counted(self):
        self.record("x1", metadata={"excluded": "Docker daemon unavailable before turn 1"})
        self.record("r1", repetition=2, rounds=[oracle_round(0)])
        text = report.render(self.runs)
        self.assertIn("- `x1` (T01/A/1): Docker daemon unavailable before turn 1", text)
        self.assertIn("(n = 1 of 30)", self.facts_row(text, 1))
        self.assertIn("Excluded: Docker daemon unavailable before turn 1", text)

    def test_interpretation_is_not_generated(self):
        self.record("r1", rounds=[oracle_round(0)])
        section = report.render(self.runs).split("## 3. Interpretation")[1].split("## Appendix")[0]
        self.assertIn("Not written.", section)

    def test_interpretation_is_included_verbatim_when_every_statement_cites_facts(self):
        text = "Arm B needed fewer corrections (F5),\nmostly on T07 and T08 (F14).\n\n- Review time is similar (F6).\n"
        self.assertIn(text.rstrip("\n"), report.render(self.runs, text))

    def test_interpretation_with_uncited_or_unknown_facts_is_rejected(self):
        text = "## Notes\n\nThe harness is clearly better.\n\n- Fewer violations (F1).\n- Cheaper (F15).\n"
        with self.assertRaises(ValueError) as raised:
            report.render(self.runs, text)
        self.assertIn("no fact cited: The harness is clearly better.", str(raised.exception))
        self.assertIn("unknown fact F15: - Cheaper (F15).", str(raised.exception))
        self.assertNotIn("Fewer violations", str(raised.exception))

    def test_cli_writes_report_and_rejects_bad_interpretation(self):
        with tempfile.TemporaryDirectory() as out:
            output, bad = os.path.join(out, "report.md"), os.path.join(out, "interpretation.md")
            with open(bad, "w", encoding="utf-8") as file:
                file.write("Uncited claim.\n")
            self.assertEqual(0, report.main([self.runs, "--output", output]))
            with open(output, encoding="utf-8") as file:
                self.assertIn("0 of 60 runs recorded", file.read())
            self.assertEqual(1, report.main([self.runs, "--interpretation", bad]))

if __name__ == "__main__":
    unittest.main()
