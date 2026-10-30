# T06: Order status history (hidden)

- **Category:** Database · **Expected areas:** `order.domain`, `order.application.*` (result type),
  `order.adapter.in.web` (response), `order.adapter.out.persistence`, new migration with backfill.

## Oracle: acceptance tests (`T06StatusHistoryOracleIT`)
`PAYMENT_PENDING` is set by SQL (not an application change, so not in the history); results are published to
`orderflow.payment.events` with the existing contract.

| Test | Expectation |
|---|---|
| `newOrderHasOneCreatedEntry` | `[{"status":"CREATED"}]`, `changedAt` equals `createdAt` (±1 s) |
| `paymentResultAddsAnEntry` | after `PaymentSucceeded`: last entry `PAID`, chronological |
| `duplicateEventAddsNoEntry` | same event twice: still exactly one `PAID` entry |
| `staleResultAddsNoEntry` | result for another attempt: history unchanged |
| `existingOrdersAreBackfilled` | Flyway to `5`, insert an order by SQL, migrate; `GET` → one `CREATED` entry at its `created_at` |
| `existingMigrationsUnchanged` | `V1`–`V5` checksums |

## Architecture traps
| Trap | Detected by |
|---|---|
| History written by the controller or Kafka listener, or in a separate transaction (`REQUIRES_NEW`) | review R1/R4 (must-fix: atomicity requirement) |
| Entry recorded for `NO_CHANGE`/duplicates | `duplicateEventAddsNoEntry` |
| History table in another schema or with a cross-schema FK | `SchemaOwnershipOracle` |
| No backfill, or backfill by application code at startup | `existingOrdersAreBackfilled`; review R4 |
| Domain annotated for JPA | `ArchitectureOracle` rules 1 and 6 |

## Reference solution outline
`Order` records `StatusChange(status, at)` entries on every transition (creation, `markPaid`, `markPaymentFailed`,
`requestPayment`) using the injected clock's time passed in by the service; persisted as child rows
(`orders.order_status_history`, FK to `orders.orders` in the same schema) in the same aggregate save;
`V6` creates the table and backfills `CREATED` from `created_at`.
Note: domain methods currently take no time argument; passing `Instant` (or a `Clock`) from the service is part of
the expected design change.
