# T04: Request payment, at most 3 attempts (hidden)

- **Category:** Business rule + database · **Expected areas:** `order.domain`, `order.application.*`,
  `order.adapter.in.web`, `order.adapter.out.persistence`, new migration.

## Oracle: acceptance tests (`T04PaymentAttemptsOracleIT`)
Failures are produced by publishing `PaymentFailed` (existing contract) for the returned `attemptId` and waiting
until `GET` shows `PAYMENT_FAILED`.

| Test | Expectation |
|---|---|
| `firstRequestStartsAttemptOne` | `202`, `attemptNumber` 1; `GET` shows `PAYMENT_PENDING` |
| `repeatedRequestReturnsTheCurrentAttempt` | same `attemptId`/`attemptNumber`, still 1 |
| `failedAttemptCanBeRetried` | after a failure: `202`, `attemptNumber` 2, new `attemptId` |
| `fourthAttemptIsRejected` | 3 failed attempts → `409` problem+json; status `PAYMENT_FAILED` |
| `paidOrderIsRejected` | after `PaymentSucceeded` → `409` |
| `unknownAndInvalidIds` | `404` / `400` problem+json |
| `existingPendingOrderCountsAsFirstAttempt` | Flyway to `5`, insert a `PAYMENT_PENDING` order by SQL, migrate to latest; fail it; then 2 more attempts are allowed and a 4th is rejected |

Because every request reloads the order from the database, a counter that is not persisted fails
`fourthAttemptIsRejected`.

## Architecture traps
| Trap | Detected by |
|---|---|
| Attempt count kept in memory or derived from something that resets | acceptance tests |
| Edited `V4`; new `NOT NULL` column without default/backfill | migration checksum; `existingPendingOrderCountsAsFirstAttempt` |
| Attempt limit enforced in the controller | review R1/R2 |
| Domain types (`PaymentAttemptId`) in `port.in` results | review R1 (project convention: plain types in `port.in`) |
| `409` for the limit implemented by mapping every `DomainException` to `409` | regression suite (create validation stays `400`) |
| Concurrent requests starting two attempts | review R5 (optimistic locking exists; should be relied on, not bypassed) |

## Reference solution outline
(`benchmark/reference/T04-payment-attempts.patch`) `Order` gains `paymentAttempts` (int, validated in `restore`);
`requestPayment()` increments it for a new attempt and throws `InvalidOrderStateException` at the limit (mapped to
`409`); `RequestPaymentUseCase` returning plain types; `V6` adds
`payment_attempt_count INT NOT NULL DEFAULT 0` and backfills `1` where `payment_attempt_id IS NOT NULL`, plus a
CHECK `0..3`; domain tests for the boundary.
