# Week 3 proof: Create Order (increment B) via the AI development workflow

Feature: `POST /api/v1/orders` persisted to PostgreSQL, built by following
[ai-development-workflow.md](../../ai-development-workflow.md). Date: 2026-10-30.
All results below come from commands executed in the session; raw logs are in this folder.

| File | Content |
|---|---|
| [quality-gate-run-1.log](quality-gate-run-1.log) | First gate run after implementation: **passed** |
| [quality-gate-run-2-fault-injected.log](quality-gate-run-2-fault-injected.log) | Deliberate fault injection to prove the tests can fail: **failed as intended** |
| [quality-gate-run-3.log](quality-gate-run-3.log) | Re-run after reverting the fault: **passed** |
| [manual-check.txt](manual-check.txt) | Real app + local PostgreSQL: curl requests and SQL queries |

## 1. Understand
- Request: `POST /api/v1/orders`; validate; generate an ID; status `CREATED`; persist in PostgreSQL via Flyway; return 201 with details; business rules in the domain; no JPA entities in the API.
- Starting point: increment A (domain, `CreateOrderUseCase`, `CreateOrderService`, `OrderRepository` port, 23 unit tests).
- Constraint checked: a global error handler would need a shared web package (rule 10 forbids Spring in `shared`). Resolution: a handler scoped to the order controllers, so no architecture change was needed.

## 2. Propose
Approved earlier as part of the two-increment Create Order plan. Refinements within that scope:
- No `total_amount` column: the domain derives the total, so storing it would duplicate data that could drift.
- The persistence test reads the JPA entity directly, instead of adding an unused `findById` to the port.
- Spring-context tests are named `*IT` (test naming rule).

## 3. Implement
| Layer | Files |
|---|---|
| Migration | `db/migration/V1__create_orders_schema.sql`: schema `orders`, tables `orders` and `order_lines` (`version` column, CHECK constraints, cascade on lines) |
| `adapter.out.persistence` | `OrderJpaEntity`, `OrderLineJpaEmbeddable` (`@ElementCollection` + `@OrderColumn`), `SpringDataOrderRepository`, `OrderMapper`, `OrderPersistenceAdapter`: all package-private |
| `adapter.in.web` | `OrderController` (depends on `CreateOrderUseCase` only), `CreateOrderRequest` (shape validation), `OrderResponse`, `OrderApiExceptionHandler` (RFC 9457 `ProblemDetail`) |
| `application.service` | `CreateOrderService`: added `@Service @Transactional` |
| `config` | `ClockConfig`: `Clock.systemUTC()` |
| Build | `spring-boot-starter-data-jpa-test` (test scope; needed for `@DataJpaTest` in Boot 4) |
| Tests | `OrderControllerIT` (5), `OrderPersistenceAdapterIT` (1), `CreateOrderIT` (2, end to end) |

## 4–7. Quality gate, inspect, fix, re-run

| Run | What | Exit | Unit | ArchUnit | Integration |
|---|---|---|---|---|---|
| 1 | After implementation | **0** | 23/23 | 10/10 | 10/10 |
| 2 | **Deliberate fault:** `OrderMapper` drops all lines (`.limit(0)`) | **1** (integration stage) | 23/23 | 10/10 | 8/10: 2 failed |
| 3 | Fault reverted | **0** | 23/23 | 10/10 | 10/10 |

Run 1 passed on the first attempt, so a fault was injected deliberately to show that the new tests detect a
real persistence regression and that the inspect → fix → re-run loop works. It is labelled as such in the log header.

**Run 2: inspection (step 5).** The integration stage failed with:
- `CreateOrderIT.persistsOrderAndReturns201:59` gave `expected: 2 but was: 0`, from the line count in `orders.order_lines`;
- `OrderPersistenceAdapterIT.savedOrderRoundTripsAllFieldsAndLineOrder:52` gave `Expecting actual: []`, for the saved lines.

Both point at lines being lost between the domain and the database. **Classification:** defect in production code
(the tests match the approved acceptance criteria). **Fix (step 6):** restore `OrderMapper`. No test was changed.
**Re-run (step 7):** the full gate, not just the failing tests, in run 3.

Integration tests in run 1 and run 3:

| Class | Tests |
|---|---|
| `OrderFlowApplicationIT` | 2 (health, readiness) |
| `OrderControllerIT` | 5: 201 + Location + body; missing fields → field errors; invalid line values; malformed JSON; domain rule → 400 |
| `OrderPersistenceAdapterIT` | 1: every field and the line order round-trip through Flyway schema + JPA |
| `CreateOrderIT` | 2: HTTP → DB row and 2 lines; invalid money → 400 and nothing persisted |

## 8. Evidence summary

**Change:** `POST /api/v1/orders` with validation, `ProblemDetail` errors and PostgreSQL persistence. Plan: approved; refinements listed in step 2.
**Quality gate:** `scripts/quality-gate.sh` → exit 0 (run 3): compile OK; unit 23 run, 0 failed; architecture 10 run, 0 failed; integration 10 run, 0 failed.
**Failures encountered:** only the deliberate fault in run 2 (integration stage), fixed by reverting it.
**Tests changed (not added):** none.
**Bypasses:** none. No skipped or disabled tests, and no ArchUnit rule changes.
**Acceptance criteria:**
- ✅ Valid POST → 201, `Location`, body with status `CREATED` (`OrderControllerIT`, `CreateOrderIT`, manual-check.txt).
- ✅ Row persisted in PostgreSQL via the Flyway V1 schema (`CreateOrderIT`, `OrderPersistenceAdapterIT`, manual check: `flyway_schema_history` v1 success).
- ✅ Invalid input → 400 with field errors, and nothing persisted (`OrderControllerIT`, `CreateOrderIT`, manual check).
- ✅ Business rules in the domain; controller depends only on `port.in`; JPA types package-private in `adapter.out.persistence` (ArchUnit 10/10).

**Open decisions / escalations:**
- `GET /api/v1/orders/{id}` doesn't exist yet: the `Location` header points to it. Next increment.
- The error handler is scoped to the order module. A global handler needs a decision on a shared web package.
- Still pending from earlier: the ArchUnit rule changes from the rules review (they would also cover JPA outside `adapter.out.persistence`).

**Not done:** not committed at the time of writing.
**Redaction:** before the first commit, the local home directory in the logs and manual-check.txt was replaced with `~`.
Nothing else was changed.
