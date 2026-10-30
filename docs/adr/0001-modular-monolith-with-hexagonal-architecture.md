# ADR-0001: Modular monolith with Hexagonal Architecture

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

OrderFlow is built incrementally over eight weeks by a small team. It starts with two business
capabilities (`order`, `payment`) and will add more, plus Kafka integration and AI-assisted processing.
We need:

- clear business boundaries so modules can evolve independently and could be extracted later;
- business logic that is testable without Spring, a database or a broker;
- low operational overhead while the domain is still being discovered.

## Decision

1. **One deployable application, split into business modules** (`order`, `payment`) under
   `com.orderflow.<module>`, plus a tiny `shared` kernel for generic value types such as `Money`.
2. **Hexagonal Architecture inside each module**: `domain`, `application` (`port.in`, `port.out`,
   `event`, `service`) and `adapter` (`in.*`, `out.*`), with dependencies pointing inward.
3. **Modules interact only through a public API**: `application.port.in` (synchronous use cases) and
   `application.event` (integration events). Integration events are preferred for state changes;
   they are delivered in-process for now and via Kafka later.
4. **Pure domain**: no framework annotations in `domain`. Persistence uses separate JPA entities and mappers.
5. **Pragmatic application layer**: `@Service` and `@Transactional` are allowed in `application.service`;
   no other framework types in `application`.
6. **Data ownership**: one PostgreSQL schema per module; no cross-module foreign keys or joins.
7. **Enforcement**: boundaries and dependency rules are checked by ArchUnit tests in the build.

Details and the full rule list: [architecture.md](../architecture/architecture.md).

## Alternatives considered

| Option | Why not (for now) |
|---|---|
| **Layered monolith** (controller/service/repository) | Simple, but packages by technical layer; business boundaries erode and domain logic couples to JPA/Spring. |
| **Microservices** | Network, deployment, data consistency and observability costs are not justified for two modules and a small team. A well-bounded monolith keeps extraction possible later. |
| **Spring Modulith** | Good fit conceptually, but adds a dependency and its own conventions. ArchUnit covers our boundary rules today. Revisit when we need its event publication registry (outbox) for Kafka. |
| **JPA annotations on domain objects** | Less mapping code, but leaks persistence concerns (no-arg constructors, lazy loading, mutable fields) into the domain. |

## Consequences

**Positive**
- Domain and use cases are unit-testable with plain JUnit.
- Module boundaries are explicit and verified on every build.
- Swapping in-process events for Kafka affects only `out.event` / `in.event` adapters.
- A module can later be extracted into a service along an existing boundary.

**Negative**
- More types and mapping code (ports, DTOs, JPA entities, mappers) than a layered design.
- Developers must learn and respect the rules; ArchUnit failures will slow initial work.
- In-process events are not durable; reliable delivery needs a follow-up decision (outbox) before production.
- Per-module schemas mean cross-module reporting needs events or read models, not SQL joins.
