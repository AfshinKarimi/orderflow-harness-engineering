# Quality tools: JaCoCo and Checkstyle

Both run in `./mvnw verify` and as stages of `scripts/quality-gate.sh`
(`compile → style → unit → architecture → integration → coverage`). Any violation fails the build.
They complement the tests and ArchUnit ([testing-strategy.md](../testing/testing-strategy.md)); they don't replace review.

## JaCoCo (code coverage)

**Setup:** `jacoco-maven-plugin` 0.8.16. One agent writes `target/jacoco.exec` for **unit and integration tests together**,
because adapters are only exercised by `*IT` tests. Report: `target/site/jacoco/index.html`.

| Rule | Line | Branch | Baseline (2026-10-30) | Why |
|---|---|---|---|---|
| Whole project | ≥ 85 % | ≥ 75 % | 97.9 % / 92.5 % | Catches large untested areas without punishing small, low-value gaps |
| Each `com.orderflow.*.domain` package | ≥ 95 % | ≥ 85 % | 100 % / 91–94 % | Business rules live here; untested domain logic should fail fast |

Thresholds sit below the baseline on purpose: with ~190 lines, one class moves the ratio by several points.
Raise them deliberately (documented here), never lower them silently to get a green build.

**Exclusions: none.** There is no generated code (no Lombok, MapStruct or OpenAPI generator); JaCoCo already ignores
compiler-generated record methods; configuration classes are covered by the integration tests.
Policy: exclude a class only if it contains no hand-written logic (e.g. generated sources), with the reason in `pom.xml`.

| Detects | Cannot detect |
|---|---|
| Code never executed by any test (lines, branches) | Whether the executed code was actually *asserted*: a test without assertions still counts |
| Untested branches (`if`, `&&`, `switch`) | Missing behaviour: code that should exist but doesn't |
| Coverage regressions when new code arrives without tests | Wrong expectations: a test asserting the wrong value still covers the line |
| | Input combinations, boundary values, concurrency, behaviour under real load |

High coverage is necessary, not sufficient. Test quality is judged in review (see the `unit-testing` skill).
To check that tests really fail, use fault injection (examples in `docs/process/evidence/`).

## Checkstyle (static source checks)

**Setup:** `maven-checkstyle-plugin` 3.6.0 with Checkstyle 14.3.0 (the bundled version is too old for current Java syntax).
Rules: [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml). Covers main and test Java sources;
resources (SQL, YAML) are out of scope. All rules are errors.

| Group | Rules | Why |
|---|---|---|
| Likely bugs | `StringLiteralEquality`, `EqualsHashCode`, `CovariantEquals`, `EmptyCatchBlock`, `FallThrough`, `InnerAssignment`, `ModifiedControlVariable`, `NeedBraces`, `DefaultComesLast`, `NoFinalizer`, `SimplifyBoolean*`, `OneStatementPerLine`, `MultipleVariableDeclarations`, `UpperEll`, `ArrayTypeStyle` | Patterns that commonly hide defects or misread logic |
| Imports | `AvoidStarImport`, `UnusedImports`, `RedundantImport`, `IllegalImport` (`sun.*`) | Explicit dependencies that are easy to review; no internal JDK APIs |
| Naming | Package (`com.orderflow…`), type, method, member, parameter, local, constant | Consistency |
| Layout | Max line length 120, no tabs, newline at end of file | Readable diffs |
| Project rules | No `System.out`/`System.err`/`printStackTrace()`; `IllegalImport` of `org.junit.jupiter.api.Disabled` and `org.junit.Ignore` | Use logging; automates hard rule 1 of the [AI development workflow](../process/ai-development-workflow.md) (no bypassed tests) |

**Suppression (one, justified):** `ConstantName` is skipped for fields annotated `@ArchTest`, because ArchUnit uses the
field name as the test name in reports (`rule1_domainDependsOnlyOnJdkAndDomain`).

Deliberately **not** enabled: javadoc-on-everything, `final` parameters, magic numbers, and Google's 2-space style.
They produce noise without catching defects in this codebase.

| Detects | Cannot detect |
|---|---|
| The syntactic patterns above, file by file | Logic errors, wrong business rules, null-safety bugs |
| Forbidden imports and calls written literally | Calls made via reflection or aliases; a test skipped by other means (`assumeTrue(false)`, a commented-out `@Test`, conditional annotations) |
| Naming and layout drift | Architecture boundaries (ArchUnit's job), cross-file design problems, duplication |
| | Security issues, performance, concurrency (would need SpotBugs, Error Prone or a SAST tool: not adopted yet) |

## Verified behaviour (2026-10-30)

| Injected fault | Result |
|---|---|
| `System.out.println` in a domain class | Gate failed at `style`: `[RegexpSinglelineJava]` |
| Test annotated `@Disabled` | Gate failed at `style`: `Illegal import - org.junit.jupiter.api.Disabled` |
| Untested domain class (4 branches) | Gate failed at `coverage`: `com.orderflow.order.domain` lines 0.83 < 0.95, branches 0.71 < 0.85 (the project-wide rule still passed) |

All faults were removed afterwards.
