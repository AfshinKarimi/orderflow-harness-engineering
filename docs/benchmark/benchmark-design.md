# Benchmark: minimal instructions vs. full engineering harness

- **Status:** Design (increment 1 of 3). No benchmark has been run; this document contains no results.
- **Date:** 2026-10-30
- **Question:** Does OrderFlow's engineering harness (instructions, skills, workflow, automated guardrails) make
  Claude Code produce changes with fewer architecture violations, more passing tests, less review effort and
  fewer correction rounds than minimal project instructions, on the same tasks under the same conditions?

## 1. Arms

Both arms start from the **same application code**: commit `69c9d98` (idempotent consumer, metrics, logging).
Only what the agent can see differs.

| Artifact | A. Minimal | B. Full harness |
|---|---|---|
| `CLAUDE.md` | [5-line version](../../benchmark/arms/A/CLAUDE.md) | as in the base commit |
| `README.md` | [setup-and-run version](../../benchmark/arms/A/README.md) | as in the base commit |
| `.claude/skills/` | removed | present |
| `docs/` (architecture, ADRs, process, testing, quality, operations) | removed | present |
| `ArchitectureTest`, `SchemaOwnershipIT` | removed | present |
| Checkstyle, JaCoCo, ArchUnit in `pom.xml`; `config/checkstyle/` | removed | present |
| `scripts/quality-gate.sh`, `.github/workflows/` | removed | present |

The exact list is in [`benchmark/arms/A/remove.txt`](../../benchmark/arms/A/remove.txt) and
[`pom-changes.md`](../../benchmark/arms/A/pom-changes.md). Everything else (code, other tests, Flyway migrations,
`compose.yaml`, `ops/`) is identical. Arm A keeps the regression tests, so both arms can run `./mvnw verify`.

Each arm is a single commit derived from the base (`bench/base-A`, `bench/base-B`); its SHA is recorded per run.

## 2. Tasks

Ten tasks in [`benchmark/tasks/`](../../benchmark/tasks), each with:

- `prompt.md`: the **exact text** sent to the agent, identical in both arms. It contains the requirements and
  acceptance criteria as an external contract (HTTP, database, Kafka), never harness vocabulary.
- `spec.md`: **hidden** from the agent: oracle tests, architecture traps, review checklist focus, reference
  solution outline.

| # | Category | Task |
|---|---|---|
| T01 | REST API | List a customer's orders with paging |
| T02 | REST API + business rule | Cancel an order |
| T03 | Business rule | Order size and amount limits |
| T04 | Business rule + database | Request payment, at most 3 attempts per order |
| T05 | Database | Shipping address on orders |
| T06 | Database | Order status history |
| T07 | Cross-module | Payment fee quote based on the order total |
| T08 | Cross-module | `OrderPlaced` event consumed by the payment module |
| T09 | Refactoring | `CustomerId` value object without contract change |
| T10 | Error handling | Uniform problem responses with correlation ID, no internal details |

Every task has known **architecture traps**: the easy solution breaks a rule (logic in controllers, cross-module
access, edited migrations, leaked internals). The traps are listed only in `spec.md`.

## 3. Execution protocol

### 3.1 Isolation: one fresh, history-free clone per run

```
git clone --depth 1 --branch bench/base-<arm> --no-local <repo> <runs>/<run-id>/work
```

- A clone with only the arm's commit: **no other branches, no history**. The agent cannot read the oracle, the
  task specs, other arms or earlier runs (`git log`/`git show` find nothing). A git worktree would share all refs
  and objects with the main repository, so it is not used.
- A fresh, empty Claude Code configuration directory per run (`CLAUDE_CONFIG_DIR=<runs>/<run-id>/claude-config`)
  with the same [`settings.json`](#33-fixed-conditions): no auto-memory, no user-level `CLAUDE.md`, no MCP servers,
  no plugins, no earlier sessions.
- The run directory is deleted after its artifacts are collected; no state carries over to the next run.

### 3.2 Conversation script (no human in the loop)

All turns are sent headless (`claude -p`, then `claude -p --resume <session>`). The texts are fixed:

| Turn | Text |
|---|---|
| 1 | `prompt.md`, verbatim |
| 2 | `Approved. Proceed with the implementation and verify it.` |
| 3+ (correction, max. 3) | template below, only if the oracle fails |

Turn 2 is always sent, in both arms, because the full harness asks for approval before coding; the minimal arm may
already have implemented the task in turn 1.

Correction template, filled from the oracle's JUnit reports (test name and the first line of the failure message,
at most 20 entries, sorted by name):

```
Automated acceptance checks failed after your change:
- <TestClass>.<method>: <first line of the failure message>
...
Fix the implementation so that these checks pass.
```

Architecture-rule failures appear in the same list (`ArchitectureOracleTest.<rule>`). The hidden tests themselves
are never shown.

### 3.3 Fixed conditions

| Condition | Value |
|---|---|
| Model | `claude-opus-5-5` (`--model`), same for every run |
| Claude Code | one pinned version, recorded per run (`claude --version`) |
| Permission mode | same mode and `settings.json` allow-list in both arms (Maven, Docker, git, file edits); no network except the Anthropic API |
| Limits | `--max-turns 200` per turn; 45 min wall clock per turn; then the turn counts as ended |
| Build | Maven offline (`-o`) with a pre-filled, read-only local repository; JDK 21 |
| Docker | same daemon and pre-pulled images (`postgres:17-alpine`, `apache/kafka:4.2.2`, Testcontainers images); no containers left from earlier runs |
| Machine | one machine; runs strictly sequential (Testcontainers timing) |
| Order | 3 repetitions × 10 tasks × 2 arms = **60 runs**, interleaved in a random order fixed by a recorded seed |
| Time window | all runs within one week, to limit model and service drift |

### 3.4 Oracle evaluation

After turn 2 and after each correction turn, the oracle runs on a **copy** of the agent's work directory (the
agent's directory is never modified by the evaluation):

1. `benchmark/scripts/prepare-oracle.sh` copies the hidden tests into the copy (package
   `com.orderflow.benchmark.oracle`): the task's tests, `ArchitectureOracleTest` (copy of rules 1–10 and 12),
   `SchemaOwnershipOracleIT`, `MigrationsUnchangedOracleTest` (base `V1`–`V5` byte-identical), and their own
   containers, plus task resources (e.g. T09's signature snapshot). It restores `archunit.properties` and adds
   ArchUnit to `pom.xml` if missing (arm A).
2. Run `./mvnw -o verify` with the regression suite (the test files of the base commit, in the agent's current
   version: adapting a test is allowed, weakening it is measured separately), the task's acceptance tests
   and the architecture oracle. Checkstyle and coverage thresholds are **not** part of the score.
   If a task deliberately changes a contract that existing tests encode (T05: the address becomes required),
   `spec.md` names those tests: HTTP-level tests are **replaced** by updated copies; tests that construct use case
   types the task legitimately changes are **excluded** from scoring for that task (`exclude.txt`). No other
   regression test is replaced or excluded.
3. Collect the JUnit XML reports.

A flaky infrastructure failure (Docker, port, timeout before any test ran) re-runs the evaluation once; it never
re-runs the agent.

### 3.5 What is stored per run

```
runs/<run-id>/
  metadata.json       task, arm, repetition, seed, base SHA, model, Claude Code version, start/end times
  transcript-<turn>.jsonl   stream-json output of every turn
  diff-<round>.patch  git diff against the arm base after turn 2 and each correction round
  oracle-<round>/     JUnit XML reports
  metrics.json        values defined in section 4
  review.json         human review record (section 4.3)
```

## 4. Metrics

All metrics are defined before any run and are computed the same way in both arms. "First done" is the state
after turn 2 (the agent's own claim of completion); "final" is the state after the last correction round.

### 4.1 Primary metrics

| Metric | Definition | Source |
|---|---|---|
| **Architecture violations** | Number of violated architecture rules on first done: failed `ArchitectureOracle` and `SchemaOwnershipOracle` rules **plus** reviewer-confirmed violations ArchUnit cannot detect (cross-schema SQL, business rules in adapters, edited migrations). Also reported for the final state. | oracle + review |
| **Test pass rate** | Passed ÷ total of (regression suite + task acceptance tests) on first done. Also reported for the final state. A test that cannot compile counts as failed. | oracle |
| **Human review effort** | Minutes a reviewer needs for a blinded review until a merge decision, and the number of findings by severity. | review |
| **Correction iterations** | Number of correction rounds until the oracle passes: 0–3. Still failing after 3 rounds: recorded as "not converged" (reported separately, and as 4 in rank statistics). | runner |

### 4.2 Secondary metrics

| Metric | Definition |
|---|---|
| False success claims | The agent states tests pass (turn 2) while its own `./mvnw verify` would fail on its final state |
| Weakened tests | Pre-existing tests deleted, `@Disabled`, or with assertions removed (from the diff) |
| Edited migrations | Any change to `V1`–`V5` (checksum comparison) |
| Scope | Files changed outside the task's expected areas (`spec.md`); unrequested dependencies in `pom.xml` |
| Cost | Input and output tokens, cost, wall-clock time, number of tool calls (from the transcripts) |

### 4.3 Human review protocol

- **Blinding:** the reviewer receives the final `src/` diff (code, tests, migrations) under a random ID, without
  transcripts, metadata or changes to `docs/`, `CLAUDE.md` and `.claude/` (these could reveal the arm).
  Documentation changes are scored separately and are not blinded.
- **Checklist** (each finding gets one item and one severity):

| Item | Question |
|---|---|
| R1 Architecture | Are module and layer boundaries respected, including what ArchUnit cannot check? |
| R2 Business rules | Are rules in the domain, each with a test including the failure case? |
| R3 Contract | Does the HTTP/event/database contract match the prompt exactly? |
| R4 Persistence | New migration instead of edited ones; constraints consistent with the domain? |
| R5 Errors | Correct status codes; no leaked internals; failures not swallowed? |
| R6 Tests | Meaningful tests at the right level; no weakened tests? |
| R7 Scope | Only what was asked; no unrequested dependencies or rewrites? |
| R8 Readability | Would a team member understand and maintain it? |

- **Severity:** *must-fix* (blocks merge: wrong behaviour, violated rule, missing test for a rule),
  *should-fix* (merge after a small change), *nit*.
- **Effort:** minutes from opening the diff to the decision "merge / merge after fixes / reject". The
  second reviewer independently reviews a random 20% of runs; agreement is reported (weighted kappa on the decision,
  difference in must-fix counts).

## 5. Analysis plan

- Per task and arm: mean of the 3 repetitions; per arm: medians and full distributions over all 30 runs.
- Paired comparison over the 10 tasks (task means A vs. B): **Wilcoxon signed-rank test** and the median paired
  difference with a bootstrap 95% confidence interval, for each primary metric.
- Hypotheses (one-sided, stated in advance): with the full harness, H1 fewer architecture violations, H2 higher
  test pass rate, H3 fewer correction iterations, H4 less review effort. Cost (tokens, time) is reported as a
  trade-off, without a hypothesis.
- Per-task results are always shown next to the aggregate, so a difference driven by one task is visible.
- No metric, threshold or exclusion rule is changed after the first run. Excluded runs (infrastructure failure
  before the agent started) are listed with the reason.

## 6. Threats to validity

| Threat | Mitigation |
|---|---|
| Model non-determinism | 3 repetitions per task and arm; distributions, not single runs |
| Task selection bias: the harness author also wrote the tasks and traps | Traps are documented up front; categories were fixed by the requester; per-task reporting; ideally an independent reviewer checks that each task is reasonable without the harness |
| Oracle bias towards one implementation | Acceptance tests use only the external contract from the prompt (HTTP, SQL, Kafka) and public use case interfaces that exist in both arms; each test must fail on the base and pass on a reference solution (increment 2) |
| Arm B's docs reveal the arm to reviewers | Review is blinded on `src/` only; docs scored separately |
| Small sample (10 tasks) | Limited statistical power; effect sizes and confidence intervals are reported, not only p-values |
| Service or model drift | Pinned model and Claude Code version, one-week window, interleaved order |
| Environment leakage between runs | Fresh history-free clone and empty Claude config per run; directory deleted afterwards |
| Infrastructure flakiness | Evaluation re-run once on infrastructure errors; agent never re-run |
| Prompt sensitivity | Identical prompts; prompts are frozen with the task files before the first run |

### 3.6 Oracle validity

Before any run, `benchmark/scripts/validate-oracle.sh <task>` checks each task's oracle on a fresh clone:

1. On the unchanged base commit, every acceptance test of the task **fails**. Tests that protect behaviour which
   must not change (e.g. "20 lines are still accepted") live in `*GuardIT`/`*GuardTest` classes and must **pass**.
2. With the reference solution (`benchmark/reference/<task>.patch`) applied, **every** test passes: regression
   suite, task oracle and architecture oracle. Reference solutions also pass the project's own quality gate.

Per-test results are kept in `benchmark/oracle/validation/`.

## 7. Increments

1. **This document, 10 task specifications, arm A definition** (done when this file and `benchmark/` are reviewed).
2. Hidden oracle tests and reference solutions per task; each acceptance test proven to fail on the base and pass
   on the reference solution.
3. Runner (clone, run turns, evaluate, correct, collect) and metric extraction, verified with a dry run that does
   not call the model. Running the benchmark itself is a separate decision.
