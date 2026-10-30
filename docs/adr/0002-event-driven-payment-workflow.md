# ADR-0002: Event-driven payment workflow with attempt-based idempotency

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

An order must be able to request payment, and the payment can succeed or fail. Order status must follow valid
transitions, and repeated requests (double clicks, concurrent calls, duplicate event delivery) must never cause a second charge.
`order` and `payment` are separate modules with separate schemas (ADR-0001). There is no message broker yet and no real
payment provider.

## Decision

1. **Modules communicate through integration events.** `order` publishes `PaymentRequested`; `payment` publishes
   `PaymentSucceeded` / `PaymentFailed`. Each module changes only its own schema, in its own transaction. There is no
   distributed transaction; consistency is eventual.
2. **Order states:** `CREATED → PAYMENT_PENDING → PAID`, `PAYMENT_PENDING → PAYMENT_FAILED → PAYMENT_PENDING` (retry).
   `PAID` is final. Invalid transitions raise `InvalidOrderStateException`, which the API returns as **409**.
   **Payment states:** `PENDING → SUCCEEDED | FAILED`, both final.
3. **Idempotency key = `PaymentAttemptId`.** The order creates one per attempt, and the payment uses it as its primary key
   and as the provider's idempotency key.
   - `requestPayment` while `PAYMENT_PENDING` returns the existing attempt.
   - Concurrent requests are serialised by optimistic locking (`version`); the loser gets 409.
   - A duplicate `PaymentRequested` never charges twice; a finished payment re-publishes its result.
   - Duplicate or stale results (old attempt ID) are no-ops on the order.
4. **Transaction boundaries:**
   - T1 (order): request payment and register the event.
   - T2 (payment): insert a `PENDING` payment.
   - Gateway call: **outside any database transaction**.
   - T3 (payment): record the result and register the event.
   - T4 (order): apply the result.
5. **Event delivery: in-process, after commit.** Recovery for lost events: repeating a payment request while
   `PAYMENT_PENDING` re-publishes `PaymentRequested` for the same attempt, which is safe because of (3).
   A transactional outbox follows with the Kafka work, in its own ADR.
6. **Retries after failure are allowed,** each as a new attempt. No retry limit for now.
7. **API:** `POST /api/v1/orders/{id}/payment-requests` returns `202 Accepted`; the outcome is read with `GET /api/v1/orders/{id}`.
8. **Gateway:** `PaymentGateway` port with a `SimulatedPaymentGateway` adapter (local profile and tests only). It declines
   amounts over 10,000.00 and approves everything else. Clients cannot choose the outcome.

## Alternatives considered

| Option | Why not |
|---|---|
| Synchronous call from `order` to `payment.port.in` | Couples the modules in time and makes the order transaction span a network call |
| One transaction spanning both modules | Breaks schema ownership (ADR-0001) and won't survive a move to Kafka or separate services |
| Transactional outbox now | Correct, but adds a table, a poller and retry logic before a broker exists; the re-publish recovery covers the gap |
| `Idempotency-Key` header + key store | More moving parts; the attempt ID derived from order state gives the same guarantee here |
| `PAYMENT_FAILED` as a final state | Forces a new order for every declined card |

## Consequences

**Positive:** no double charges by construction; the modules stay independent; the gateway call holds no database
resources; delivery mechanisms can be swapped (in-process → Kafka) in the event adapters only.

**Negative:** eventual consistency, so clients poll `GET /orders/{id}`; more states and tests; in-process events can be lost on a crash
(mitigated by the re-publish recovery, fixed properly by an outbox).

**Open points:**
- Reconciliation for unknown gateway outcomes (timeout or crash during `charge`): the payment stays `PENDING` and is never assumed failed.
- Transactional outbox.
- Retry limits once a real provider exists.
