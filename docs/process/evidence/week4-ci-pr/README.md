# Week 4 proof: a PR that fails CI on a rule violation, then turns green after a fix

Date: 2026-10-30. Repository: https://github.com/AfshinKarimi/orderflow-harness-engineering (public).
All facts below come from `gh` / the GitHub API; raw CI logs are in this folder.

| File | Content |
|---|---|
| [ci-run-1-red.log](ci-run-1-red.log) | "Run quality gate" step of the failing PR run |
| [ci-run-2-green.log](ci-run-2-green.log) | "Run quality gate" step of the passing PR run |

## Setup

**First CI run on `main`:** [run 41075817334](https://github.com/AfshinKarimi/orderflow-harness-engineering/actions/runs/41075817334) (push, `6377ba1`): **success**.
Same results as locally: Checkstyle 0 violations; unit 26, ArchUnit 10, integration 13 run, 0 failed; coverage met.

**Branch protection on `main`** (read back from the API after enabling):

| Setting | Value | Why |
|---|---|---|
| Required status check | `Quality gate` from GitHub Actions (app 15368) | No merge without the full gate; no other app can satisfy it |
| Branch must be up to date (`strict`) | true | The gate must have passed against the current `main` |
| Enforce for admins | true | The repository owner cannot bypass it either |
| Required reviews | none | Single developer: GitHub does not allow approving your own PR |
| Force pushes / deletion | blocked | History of `main` cannot be rewritten |
| Conversation resolution | required | Review comments must be resolved before merging |

## The test PR: [#1](https://github.com/AfshinKarimi/orderflow-harness-engineering/pull/1)

| Commit | Change | CI run | Result | PR merge state |
|---|---|---|---|---|
| `98b8c4c` | `CreateOrderService` adds `org.springframework.util.Assert.notEmpty(command.lines(), …)` | [41076238013](https://github.com/AfshinKarimi/orderflow-harness-engineering/actions/runs/41076238013) | ❌ **failure**: stage `unit`, 26 run / 2 failed | **BLOCKED** |
| `c862464` | Remove the `Assert` (source identical to `main` again) | [41076509487](https://github.com/AfshinKarimi/orderflow-harness-engineering/actions/runs/41076509487) | ✅ **success**: all 6 stages | **CLEAN** (mergeable) |

The PR was closed without merging, because its net diff is zero.

### What the red run caught

The plan expected the failure at the `architecture` stage (ArchUnit rule 3: application code may use only `@Service`
and `@Transactional` from Spring). CI actually failed **earlier, at `unit`**:

```
[ERROR] CreateOrderServiceTest.treatsMissingLinesAsEmptyOrder <<< FAILURE!
Expecting actual throwable to be an instance of:
  com.orderflow.order.domain.InvalidOrderException
[ERROR] CreateOrderServiceTest.doesNotSaveInvalidOrder <<< FAILURE!
Expecting actual throwable to be an instance of:
  com.orderflow.order.domain.InvalidOrderException
[ERROR] Tests run: 26, Failures: 2, Errors: 0, Skipped: 0
QUALITY GATE FAILED: stage 'unit' (exit code 1)
```

**Inspection:** Spring's `Assert` throws `IllegalArgumentException`, not the domain's `InvalidOrderException`.
The API error handler maps only `DomainException`s to 400, so in production this change would have turned a 400 into a **500**.
The one change broke two rules:

1. **The domain error contract.** Caught in CI by the unit tests (above).
2. **ArchUnit rule 3.** Confirmed locally on the same commit before pushing:
   `ArchitectureTest.rule3_applicationUsesOnlyServiceAndTransactional ... Method <CreateOrderService.create(...)> calls method <org.springframework.util.Assert.notEmpty(...)>`
   (10 run, 1 failed). In CI the gate stops at the first failing stage, so `architecture` was not reached.

**Classification:** defect in the change (the tests and rules were correct). **Fix:** remove the guard. "At least one line" is a
business rule the domain (`Order.create`) already enforces with the right exception. No test or rule was changed.

### Green run (excerpt)

```
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0      (unit)
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0      (ArchUnit)
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0      (integration)
[INFO] All coverage checks have been met.
==> QUALITY GATE PASSED
```

## Evidence summary
- **Rule violation blocked:** yes. The PR was red and GitHub reported the merge as `BLOCKED` while the required check failed.
- **Fix turns it green:** yes. Same PR, next commit: `SUCCESS`, merge state `CLEAN`.
- **Bypasses:** none. No test, ArchUnit rule or threshold was changed; no admin override (admins are included in protection).
- **Lesson:** fail-fast ordering means the *first* broken rule is what CI reports. A change can break several rules, so
  fixing the reported one does not guarantee the next stage passes. Always re-run the whole gate.
- **This evidence** reached `main` through its own PR, under the same protection.
