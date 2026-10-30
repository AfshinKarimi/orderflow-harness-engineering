---
name: orderflow-code-review
description: Review an OrderFlow change (working tree diff, branch or files) for correctness, architecture compliance, tests, documentation and the project's Definition of Done, producing ranked findings. Use before declaring an increment done, before a commit or PR, or when asked to review OrderFlow code. For a deep multi-agent bug hunt, the built-in /code-review is the alternative.
---

# OrderFlow code review

## Purpose
Decide whether a change is ready, against this project's standards: correctness first, then the architecture
(`docs/architecture/architecture.md`), tests, docs, and the Definition of Done in CLAUDE.md.
Review only; don't change code unless asked.

## When to use
- At the end of an increment, before reporting it done.
- Before a commit or PR is requested.
- When asked to review specific OrderFlow files or a diff.

## Workflow
1. **Scope.** `git status` and `git diff` (plus new untracked files), or the files or branch named by the user. Restate the intended change and the approved plan, if there is one, so scope creep is visible.
2. **Correctness.** For each changed file:
   - null and empty handling;
   - `BigDecimal` scale and rounding (money is never rounded silently);
   - equality and immutability of records and value objects;
   - exception types (`DomainException` subtype for business errors);
   - transaction boundaries;
   - error-to-HTTP mapping;
   - SQL and Flyway: correct types and constraints, and existing migrations unchanged.
3. **Architecture.** Apply the `architecture-review` skill's checklist to the changed classes. Note the ArchUnit result.
4. **Tests.**
   - Every new behaviour and failure case is tested at the right level (see the `unit-testing` skill for domain and services).
   - No skipped or `@Disabled` tests, and no weakened assertions.
   - Test names match where they run: `*Test` with no Spring context, `*IT` otherwise.
   - Run `scripts/quality-gate.sh` (Docker running) and record the actual counts per stage.
5. **Simplicity.** Flag unnecessary abstractions, unused code, dependencies without justification, and duplicated logic. Prefer the smallest fix.
6. **Docs.** README, `architecture.md` and ADRs are updated where the change requires it. No stale statements.
7. **Leftovers.** No temporary, debug or intentionally broken code (`grep -rn "TEMPORARY\|TODO\|System.out" src/main`), and no commented-out code.
8. **Rank and verify findings.** Confirm each finding by reading the code, and drop speculative ones. Order by severity:
   - **Blocker:** incorrect behaviour, data loss, a security issue, an architecture violation, or a failing build.
   - **Major:** a missing test for a business rule, a breaking public contract, missing docs for a decision.
   - **Minor:** clarity, naming, simplification.

## Quality checklist
- [ ] Build executed; real test counts included.
- [ ] Every finding has `file:line`, a concrete failure scenario or reason, and a suggested fix.
- [ ] The architecture checklist was applied, including the constraints ArchUnit can't enforce.
- [ ] Scope matches the approved plan; extras are flagged.
- [ ] Definition of Done items in CLAUDE.md are checked one by one.
- [ ] No unverified claims ("probably fine", "tests should pass").

## Expected output
1. **Verdict:** `Ready` · `Ready after minor fixes` · `Not ready`.
2. **Build result:** the command plus the actual `Tests run / Failures / Errors` and BUILD status.
3. **Findings table:** severity · `file:line` · issue · fix.
4. **Definition of Done:** each item ✅ / ❌ with a one-line note.
5. **Optional follow-ups,** kept clearly separate from the required fixes.
