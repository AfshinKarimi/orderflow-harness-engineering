# OrderFlow

AI-assisted order processing platform. Modular monolith following Hexagonal Architecture.

- Architecture: [docs/architecture/architecture.md](docs/architecture/architecture.md)
- Decisions: [docs/adr](docs/adr)
- Metrics, dashboard, logging: [docs/operations/observability.md](docs/operations/observability.md) · troubleshooting: [runbook](docs/operations/runbook.md)

## Stack
Java 21 · Spring Boot 4.1 · Maven (wrapper) · PostgreSQL 17 · Flyway · Kafka 4.2 (transactional outbox) · Testcontainers

## Prerequisites
- JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS)
- Docker (tests start PostgreSQL and Kafka via Testcontainers)

## Build and test
```bash
scripts/quality-gate.sh   # compile → Checkstyle → unit → ArchUnit → integration → JaCoCo coverage; non-zero exit on first failure
./mvnw clean verify       # same checks in one Maven run
```
CI (`.github/workflows/ci.yml`) runs the same `scripts/quality-gate.sh` on pull requests to `main` and pushes to `main`,
writes a per-stage table to the run summary, and attaches all reports as the `quality-reports` artifact.

Coverage report: `target/site/jacoco/index.html`. Thresholds and rules: [docs/quality/quality-tools.md](docs/quality/quality-tools.md).
Test naming: `*Test` runs without Spring/Docker (Surefire); `*IT` needs a Spring context or Docker (Failsafe).

## Run locally
```bash
docker compose up -d          # PostgreSQL on localhost:5432, Kafka on localhost:9092
                              # add --profile observability for Prometheus (:9090) and Grafana (:3000)
./mvnw spring-boot:run        # uses the "local" profile by default
curl localhost:8080/actuator/health
```

## Profiles
| Profile | Purpose | Database and Kafka |
|---|---|---|
| `local` (default) | Developer machine | `compose.yaml` PostgreSQL and Kafka |
| `prod` | Deployed environments | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` env vars (required); Actuator on port `MANAGEMENT_PORT` (default 8081) |

Tests run with the default `local` profile, but Testcontainers replaces the database and Kafka connections via
`@ServiceConnection`. Test-only overrides (outbox relay disabled, short Kafka timeouts) live in
`src/test/resources/config/application.yml`.

Consumed events that keep failing are moved to `<topic>.DLT` (e.g. `orderflow.payment.events.DLT`) after 3 attempts.

## API
| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/orders` | `201` + `Location`; `400` `application/problem+json` on invalid input |
| `GET` | `/api/v1/orders/{id}` | `200` with the order; `404` problem if unknown; `400` if the ID is not a UUID |

```bash
curl -i -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"c-1","currency":"EUR","lines":[{"productId":"p-1","quantity":2,"unitPrice":19.99}]}'
```

## Health and metrics
- `/actuator/health`: overall status (details shown only in `local`)
- `/actuator/health/liveness`, `/actuator/health/readiness`: probe endpoints
- `/actuator/prometheus`: Prometheus scrape endpoint; `/actuator/metrics`: browsable list
- In `prod` these are served on the management port (8081), not the API port.

## Database migrations
Flyway owns the schema (`src/main/resources/db/migration`); Hibernate runs with `ddl-auto: validate`.
