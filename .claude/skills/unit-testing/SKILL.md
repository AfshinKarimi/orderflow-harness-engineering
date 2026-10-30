---
name: unit-testing
description: Write or review OrderFlow unit tests for domain objects and application services (plain JUnit 5 + AssertJ, no Spring context). Use when adding or changing business rules, value objects, aggregates or use case services, or when checking whether existing tests cover a change.
---

# Unit testing

## Purpose
Prove every business rule and use case outcome with fast, deterministic tests that need no Spring context,
database or Docker. Test strategy by layer is defined in CLAUDE.md ("Testing expectations").
This skill covers the unit level only; adapters use slice tests and Testcontainers.

## When to use
- New or changed code in `*.domain` or `*.application.service` (or `shared.domain`).
- Reviewing whether a change is adequately tested.
- Not for controllers, JPA mapping or Flyway: use `@WebMvcTest`, `@DataJpaTest` or `@SpringBootTest`.

## Workflow
1. **List behaviours.** For the class under test, enumerate every invariant, branch, calculation and exception from the code. Each one becomes at least one test, with failure cases included.
2. **Place the test.** Same package under `src/test/java`, named `<Class>Test`.
3. **Write tests:**
   - Method names describe behaviour: `rejectsMixedCurrencies`, `totalIsSumOfLineTotals`.
   - Arrange / act / assert, one behaviour per test; small private factory helpers (`line(...)`, `eur(...)`) for readability.
   - Exceptions: `assertThatThrownBy(...).isInstanceOf(<specific DomainException subtype>).hasMessage(...)`. Assert the message when clients will see it.
   - Values: compare `Money` and records by equality. Use `isEqualByComparingTo` only for raw `BigDecimal` where the scale doesn't matter.
4. **Isolate application services:**
   - Ports: hand-written fakes as private static nested classes (e.g. `InMemoryOrderRepository`). Use Mockito only when a fake would be clearly worse, and say why.
   - Time: `Clock.fixed(...)`; never `Instant.now()` in assertions.
   - Generated IDs: assert relationships (the result ID equals the saved ID; two calls give different IDs), not values.
   - Invalid input: assert the exception **and** that nothing was saved or published.
5. **Run and report.** Run the class (`./mvnw test -Dtest=<Class>`), then `scripts/quality-gate.sh`. Report actual counts. If a test fails, report it and fix the code or the test for a stated reason; never weaken an assertion just to pass.

## Quality checklist
- [ ] Every domain rule has a passing case and a failing case.
- [ ] No Spring annotations, context, DB or network in unit tests.
- [ ] Tests are deterministic: fixed clock, no ordering assumptions, no sleeps.
- [ ] Specific exception types are asserted, not `RuntimeException`.
- [ ] Immutability and defensive copies are tested where the domain promises them.
- [ ] Invalid-input tests verify that there were no side effects.
- [ ] Test names read as specifications.
- [ ] Tests were executed and real counts reported.

## Expected output
1. A coverage table: behaviour / rule → test method.
2. The test code (new or changed files).
3. The actual output of the test run: per-class counts and the overall `Tests run / Failures / Errors`.
4. Any gaps knowingly left untested, with the reason.
