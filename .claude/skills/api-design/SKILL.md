---
name: api-design
description: Design or change an OrderFlow REST endpoint (path, request/response DTOs, status codes, validation, error format) before implementing it. Use when adding or modifying anything under /api/v1, or when a use case needs an HTTP entry point.
---

# API design

## Purpose
Produce a REST endpoint spec that fits the hexagonal boundaries: HTTP concerns stay in `adapter.in.web`,
business rules stay in `domain`, and the API never exposes domain or JPA types.
Boundaries and layer rules: `docs/architecture/architecture.md`.

## When to use
- A new use case needs an HTTP endpoint, or an existing endpoint changes.
- Reviewing a proposed request/response shape or error behaviour.
- Not for internal module-to-module calls: those use `port.in` / `application.event`.

## Workflow
1. **Identify the use case.** Name the `port.in` interface it calls (existing or new). If none exists, design the use case first; the endpoint is only a translation layer.
2. **Resource and path.** `/api/v1/<plural-noun>` (e.g. `/api/v1/orders`), `/{id}` for a single resource, nouns not verbs. State transitions that aren't plain CRUD use a sub-resource (e.g. `POST /api/v1/orders/{id}/cancellation`); mention the trade-off.
3. **Request DTO** (record in `adapter.in.web`), validated with Bean Validation for *shape only*: required fields, ranges, formats, collection size. List which checks are shape (DTO) and which are business rules (domain); the domain must enforce its rules even if the DTO also checks them.
4. **Mapping.** DTO → `port.in` command, and `port.in` result → response DTO. Never return `OrderResult` or any domain/JPA class directly.
5. **Response DTO.** Conventions:
   - IDs as UUID strings; timestamps ISO-8601 UTC (`Instant`).
   - Money as a decimal number plus an ISO-4217 `currency` code; never floats in the domain.
   - Enums as upper-case strings.
6. **Status codes.**

   | Case | Status |
   |---|---|
   | Created | `201` + `Location: /api/v1/<plural>/{id}` + body |
   | Read / update OK | `200` |
   | Malformed input or `DomainException` | `400` |
   | Unknown ID | `404` |
   | Conflicting state (e.g. already cancelled) | `409` |

7. **Errors.** RFC 9457 `ProblemDetail` (Spring built-in) from one `@RestControllerAdvice`. Field errors are listed under an `errors` property; domain errors use the exception message as `detail`. No stack traces or internal class names.
8. **Compatibility.** Additive changes only within `v1` (new optional fields, new endpoints). Removing or renaming fields, or changing their meaning, is breaking: flag it and ask for a decision.
9. **Files and tests.** List the controller, DTOs, mapper, exception handler changes, and the tests (`@WebMvcTest` for every status code, plus one end-to-end `@SpringBootTest` per endpoint).

## Quality checklist
- [ ] Controller depends only on `port.in` (ArchUnit rule 4).
- [ ] No domain, JPA or `port.in` result type appears in a request or response.
- [ ] Every business rule is enforced in `domain`, not only in the DTO.
- [ ] Every error path has a defined status and `ProblemDetail` example.
- [ ] `201` responses include `Location`.
- [ ] Money has an explicit currency; no `double`/`float`.
- [ ] Breaking changes are called out explicitly.
- [ ] Each status code has a planned `@WebMvcTest`.

## Expected output
A spec for approval (no code yet), containing:
1. Endpoint table: method, path, use case, success status.
2. Request and response JSON examples.
3. Validation table: field → rule → where enforced (DTO / domain).
4. Error cases: condition → status → example `ProblemDetail`.
5. Files to create or change, and the tests planned.
6. Open decisions, each with a recommendation.
