# Evaluation report template

The benchmark report is **generated** from the run records by `benchmark/scripts/report.py`; it is never written
by hand, except for the interpretation. Protocol and metric definitions: [benchmark-design.md](benchmark-design.md).

```bash
python3 benchmark/scripts/report.py <runs-dir> [--interpretation interpretation.md] --output report.md
```

## Rules

1. **Only recorded values.** Every number comes from `runs/<run-id>/{metadata,metrics,review}.json`
   (fields: design section 3.5). Nothing is estimated, defaulted or carried over from another run.
2. **Missing is not zero.** A missing value is printed as `not recorded` and left out of every aggregate.
   Each aggregate states its n ("n = 27 of 30"), so incomplete data stays visible.
3. **Invalid records are shown, not dropped.** A record that breaks the format or the protocol (unknown task or
   arm, more than 3 correction rounds, a correction after the oracle passed, two records for one slot) is listed
   with the reason under Completeness and is not counted.
4. **Facts and interpretation are separate sections.** Sections 1, 2 and the appendix are measured facts,
   computed by the script. Section 3 is written by a person in a separate file and included verbatim. The
   script rejects the file if any paragraph or bullet cites no fact ID (`F1`-`F14`) or an unknown one.
5. **No hypothesis tests yet.** The report is descriptive. The tests of section 5 of the design (H1-H4) are listed
   as "not computed".

## Structure

### 1. Completeness

Per arm: planned (30), recorded, excluded, awaiting evaluation (no `metrics.json`), awaiting review (no
`review.json`). Then the slots without a record, excluded runs with their reason, and invalid records with theirs.

### 2. Measured facts

Per arm, over the runs that are recorded and not excluded:

| ID | Fact | Value |
|---|---|---|
| F1 | Architecture violations, first done | hidden architecture rules failed after turn 2: median, mean, n |
| F2 | Architecture violations, final | hidden rules failed in the last round **plus** reviewer-confirmed violations: median, mean, n |
| F3 / F4 | Test pass rate, first done / final | (regression + task acceptance) passed ÷ total: median, mean, n |
| F5 | Correction iterations | number of runs with 0, 1, 2, 3, not converged |
| F6 | Human review time | minutes: median, mean, n |
| F7 | Review findings | total must-fix, should-fix, nit |
| F8 | Final outcome | number of runs per outcome |
| F9 | False success claims | runs |
| F10 / F11 / F12 | Weakened tests / edited migrations / out-of-scope files or new dependencies | runs |
| F13 | Cost | input tokens, output tokens, wall-clock minutes: median, mean, n |
| F14 | Per task and arm | means of F1, F3, F6; correction iterations and outcome per repetition |

The reviewer sees only the final diff, so F1 counts only the hidden rules, while F2 adds the reviewer-confirmed
violations.

### 3. Interpretation

Written by a person in its own file. Every statement cites the facts it relies on, for example:

```markdown
- Arm B converged without corrections more often (F5), mainly on the cross-module tasks T07 and T08 (F14).
- Review time differs little between the arms (F6); the difference in must-fix findings (F7) comes from R1.
```

Not allowed: statements without a fact ID, causal claims the facts cannot support, and results from runs that
are not in the records.

### Appendix: one record per run

| Field | Recorded value | Source |
|---|---|---|
| Task ID | `T01`-`T10` | `metadata.json` |
| Model and configuration | model ID, Claude Code version, permission mode, max. turns, turn timeout | `metadata.json` |
| Harness configuration | arm (A minimal instructions, B full engineering harness), SHA of the starting commit | `metadata.json` |
| Code changes (final diff) | files and lines changed; out-of-scope files; new dependencies; edited migrations; weakened tests | `metrics.json` `code_changes` |
| Architecture tests, first done / final | number and names of failed hidden architecture rules; final adds reviewer-confirmed violations | `metrics.json` `rounds`, `review.json` |
| Functional tests, first done / final | pass rate; regression and task acceptance passed/total | `metrics.json` `rounds` |
| Correction attempts | 0-3, or `not-converged` | derived from `rounds` |
| Human review | minutes, decision, findings per severity, checklist items | `review.json` |
| Final outcome | see below | derived |

**Final outcome**, one per run, derived by these rules in order:

| Outcome | Rule |
|---|---|
| `excluded` | `metadata.excluded` is set (infrastructure failure before the agent started) |
| `not-converged` | the oracle still fails after 3 correction rounds, whatever the review says |
| `accepted` | the oracle passes and the review decision is `merge` |
| `accepted-after-fixes` | the oracle passes and the review decision is `merge-after-fixes` |
| `rejected` | the oracle passes and the review decision is `reject` |
| `not recorded` | not evaluated or not reviewed yet |
