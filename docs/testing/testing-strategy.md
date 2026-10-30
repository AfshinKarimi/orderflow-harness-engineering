# Testing strategy

How OrderFlow is tested, layer by layer. Naming, commands and the Definition of Done are in [CLAUDE.md](../../CLAUDE.md);
the gate is `scripts/quality-gate.sh`. Principle: **test each rule once, at the lowest layer that can prove it**,
and add higher-layer tests only for what that layer adds (wiring, mapping, serialization, SQL).

## Layers

| Layer | Proves | Tools | Naming / stage | Doubles |
|---|---|---|---|---|
| **Domain** (`*.domain`, `shared.domain`) | Every invariant, calculation and state rule, each with a pass and a fail case | JUnit 5, AssertJ | `*Test` / unit | None: real value objects |
| **Application service** (`*.application.service`) | Orchestration: inputs mapped to the domain, port called (or not called on failure), result mapped, clock and ID used | JUnit 5, AssertJ | `*Test` / unit | **Hand-written fakes** for ports (state-based); `Clock.fixed` |
| **REST controller** (`*.adapter.in.web`) | HTTP contract: status, headers, JSON shape, request validation, error mapping to `ProblemDetail`, use case not called on invalid input | `@WebMvcTest`, MockMvc | `*IT` / integration | **Mockito** (`@MockitoBean`) for the `port.in` use case |
| **Repository** (`*.adapter.out.persistence`) | Mapping domain → JPA → SQL against the real Flyway schema; column types; ordering; constraints the code relies on | `@DataJpaTest` + Testcontainers PostgreSQL | `*IT` / integration | None: real database |
| **End to end** (one per use case) | The slices are wired together: HTTP → service → DB, transaction, JSON number precision | `@SpringBootTest` + MockMvc + Testcontainers | `*IT` / integration | None |
| **Architecture** | Dependency and boundary rules from [architecture.md](../architecture/architecture.md) | ArchUnit | `ArchitectureTest` / architecture | n/a |

Why these tools:
- **Mockito only at the web slice.** There it isolates HTTP behaviour from the use case, and interaction checks (`verifyNoInteractions`) are the point. For ports in service tests, fakes give clearer state-based assertions and survive refactoring better.
- **Testcontainers, never H2.** Flyway SQL, `NUMERIC` scale, `TIMESTAMPTZ` and `CHECK` constraints are PostgreSQL behaviour. Every Spring context in tests also runs Hibernate `ddl-auto: validate` against the Flyway schema, so entity/schema drift fails every IT.

Metrics: services assert what they report through a fake metrics port (unit); counting after commit, the Prometheus
output and the cardinality guard are tested in `ObservabilityIT`, and consumer/outbox metrics inside their existing
scenario ITs ([observability.md](../operations/observability.md)).

## What each layer must *not* re-test
- Controllers don't re-test domain rules: one representative `DomainException` → 400 mapping is enough.
- Repository tests don't re-test validation: the domain guarantees valid objects.
- End-to-end tests cover the happy path plus one failure path per use case. Combinatorial input cases belong in domain and controller tests.

## Current coverage (2026-10-30)

| Class | Layer | Tests | Assessment |
|---|---|---|---|
| `MoneyTest` | Domain | 11 | Complete for current behaviour (G1 added) |
| `OrderTest` | Domain | 11 | Complete for current behaviour (G2 added) |
| `CreateOrderServiceTest` | Application | 4 | Complete for current behaviour (G3 added) |
| `OrderControllerIT` | REST | 6 | G4 added; G5 asserted on both error paths (validation and domain) |
| `OrderPersistenceAdapterIT` | Repository | 1 | Sufficient for a save-only port |
| `CreateOrderIT` | End to end | 2 | Sufficient. The invalid case is *not* redundant: it proves a JSON `10.005` reaches the domain as an exact `BigDecimal` |
| `OrderFlowApplicationIT` | End to end | 2 | Health and readiness: actuator configuration |
| `SchemaOwnershipIT` | Architecture (DB) | 2 | G6: rule 11, plus a check that the query actually finds foreign keys |
| `ArchitectureTest` | Architecture | 10 | Gaps proposed separately (rules review A–E) |

No existing test was found redundant. Gate result after G1–G6: unit 26, architecture 10, integration 13. All passed.

## Missing test cases (all added 2026-10-30)

The guard tests G4 and G6 were also proven to fail when what they guard is broken (element `@NotNull` removed:
NPE instead of 400; temporary V2 migration with a cross-schema foreign key: reported). The faults were then reverted.

| ID | Where | Case | Why it matters |
|---|---|---|---|
| G1 | `MoneyTest` | Rejects a currency without minor units (e.g. `XXX`) | Untested branch; otherwise `setScale(-1)` would corrupt amounts |
| G2 | `OrderTest` | Line rejects a missing (`null`) unit price | Untested branch in `OrderLine` |
| G3 | `CreateOrderServiceTest` | Command with `lines = null` → `InvalidOrderException`, nothing saved | Untested branch in the service mapping |
| G4 | `OrderControllerIT` | `"lines":[null]` → 400 with a field error | Without the element `@NotNull`, `toCommand()` throws NPE → 500 |
| G5 | `OrderControllerIT` | Error responses have `Content-Type: application/problem+json` | Part of the API contract; add an assertion to an existing test, not a new test |
| G6 | `SchemaOwnershipIT` (new) | No foreign key references a table in another module's schema | Automates architecture rule 11, which ArchUnit cannot see; guards the moment `payment` gets tables |

### Deliberately not tested
| Case | Reason |
|---|---|
| `null` ID / `createdAt` passed to `Order.create` | Programming error (`requireNonNull`), not a business rule |
| `@Size(max=100)` lines, `@Digits` on price, `415` for non-JSON | Bean Validation / Spring behaviour; would test the framework |
| JSON with wrong value types | Same code path as malformed JSON (already covered) |
| DB `CHECK` constraints in isolation | The domain prevents those values; constraints are a safety net |
| Optimistic locking (`version`), loading orders back | No update or read use case yet: add with `GET /orders/{id}` |

## Findings outside test scope
- **Unhandled exceptions** fall through to Spring Boot's default error response, not `ProblemDetail`. This is an API consistency decision, to make when a global error handler is designed.
- **Two PostgreSQL containers start per run** (one for the `@DataJpaTest` context, one shared by the `@SpringBootTest` contexts). That's acceptable at today's size (integration stage ≈ 7 s). Revisit with a singleton container if the stage gets slow.
