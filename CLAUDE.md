# OrderFlow: guide for Claude

Act as a senior Java engineer and architecture reviewer.
Every change follows the [AI development workflow](docs/process/ai-development-workflow.md):
understand → propose → implement small → quality gate → inspect → fix → re-run → summarize evidence.

## Project overview
AI-assisted order processing platform, built incrementally over eight weeks.
Java 21 · Spring Boot 4.1 · Maven wrapper · PostgreSQL 17 · Flyway · Testcontainers · ArchUnit (Kafka, Docker image, GitHub Actions planned).
Setup, profiles and endpoints: [README.md](README.md).

## Architecture boundaries
Modular monolith (`order`, `payment`, small `shared` kernel) with Hexagonal Architecture inside each module.
Full rules: [docs/architecture/architecture.md](docs/architecture/architecture.md) · rationale: [ADR-0001](docs/adr/0001-modular-monolith-with-hexagonal-architecture.md).

The essentials:
- Layout: `com.orderflow.<module>.{domain, application.{port.in, port.out, event, service}, adapter.{in.*, out.*}}`.
- Dependencies point inward: `adapter → application → domain`.
- `domain` is plain Java: no Spring, JPA, Jackson or validation annotations.
- `application` may use only `@Service` and `@Transactional` from Spring.
- Inbound adapters use `port.in` only: never services, `port.out` or repositories.
- Other modules are reached only via their `application.port.in` / `application.event`, and only from `adapter.in`.
- JPA entities are separate classes in `adapter.out.persistence`, never exposed through REST.
- Each module owns its own DB schema; no cross-module foreign keys or joins (not checkable by ArchUnit; review it).

Rules are enforced by `src/test/java/com/orderflow/architecture/ArchitectureTest.java`.

## Coding conventions
- Value objects, commands, results and events are `record`s; aggregates are classes with a static factory (`Order.create`).
- Business rule violations throw a subclass of `shared.domain.DomainException`; `Objects.requireNonNull` is for programming errors.
- `port.in` types expose plain types (`UUID`, `String`, `BigDecimal`), not domain classes.
- Money uses `shared.domain.Money`; amounts with too many decimals are rejected, never rounded.
- Constructor injection only. Time comes from an injected `java.time.Clock`.
- No Lombok. No new dependency without a stated justification.
- Schema changes go only through new Flyway migrations in `src/main/resources/db/migration`; Hibernate runs with `ddl-auto: validate`.

## Testing expectations
Strategy, per-layer scope and known gaps: [docs/testing/testing-strategy.md](docs/testing/testing-strategy.md).
Coverage thresholds and Checkstyle rules: [docs/quality/quality-tools.md](docs/quality/quality-tools.md).
- Domain and application services: plain JUnit 5 + AssertJ unit tests, no Spring context. Prefer hand-written fakes for ports over Mockito.
- Naming decides where a test runs: `*Test` = no Spring context (Surefire); `*IT` = starts a Spring context or needs Docker (Failsafe).
- Adapters: Spring slice tests (`@WebMvcTest`, `@DataJpaTest`), named `*IT`.
- Database tests use Testcontainers PostgreSQL (`TestcontainersConfiguration`), never H2. **Docker must be running.**
- Every business rule gets a test, including the failure case.
- Architecture changes get an ArchUnit rule, proven to fail on a temporary violation that is then deleted
  (example: [docs/architecture/archunit-violation-demo.md](docs/architecture/archunit-violation-demo.md)).

## Commands for verification
All verified on 2026-10-30. The default `java` on this machine is 26, so select JDK 21 first:

```bash
scripts/quality-gate.sh                              # compile → style → unit → ArchUnit → integration → coverage; stops at first failure (picks JDK 21 itself)
                                                     # coverage report: target/site/jacoco/index.html
                                                     # CI (.github/workflows/ci.yml) runs this same script

export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw clean verify                                  # full build: compile + all tests (needs Docker)
./mvnw test -Dtest=ArchitectureTest                  # architecture rules only
./mvnw test -Dtest='OrderTest#rejectsMixedCurrencies' # single test class or method

docker compose up -d                                 # local PostgreSQL for running the app
./mvnw spring-boot:run                               # starts on :8080 with profile "local"
curl localhost:8080/actuator/health                  # expect "status":"UP"
docker compose stop
```

## Definition of Done
1. The plan (assumptions, files, acceptance criteria) was approved before coding.
2. Tests cover the change, including failure cases.
3. `scripts/quality-gate.sh` passes, and the **actual** test counts are reported.
4. ArchUnit passes, and no rule was weakened or skipped to make it pass.
5. Docs are updated where affected: `architecture.md` for rules, a new ADR for architectural decisions, `README.md` for commands and setup.
6. No temporary, debug or intentionally broken code is left behind.
7. Nothing is committed or pushed unless explicitly requested.

## Rules for modifying existing code
1. Never implement an entire milestone at once; work in small, reviewable increments.
2. Before coding, explain the plan, assumptions, files to change and acceptance criteria.
3. Wait for approval before implementing.
4. Prefer simple, maintainable solutions over unnecessary abstractions.
5. Do not introduce dependencies without justification.
6. After each change, run the relevant tests and report the actual results.
7. Never claim tests passed unless they were executed.
8. Explain architecture trade-offs and ask for a decision when necessary.
9. Do not commit or push unless explicitly requested.
10. Maintain concise engineering documentation.
11. Never edit a Flyway migration that may have been applied; add a new one.
12. Never relax, delete or `@Disabled` an ArchUnit rule or a failing test to get a green build. Raise it instead.
13. Treat `application.port.in` and `application.event` as public contracts; call out any breaking change.
14. Read the code and the relevant docs before changing them; keep changes within the agreed scope.
