# T08: `OrderPlaced` consumed by the payment module (hidden)

- **Category:** Cross-module dependency · **Expected areas:** `order.application.event` (event record),
  `order.application.port.out` + `order.adapter.out.event` (publisher via outbox), `order.application.service`,
  `payment.adapter.in.event`, `payment.application.*`, `payment.adapter.out.persistence`, new migration (`payment`
  schema).

## Oracle: acceptance tests (`T08OrderPlacedOracleIT`, relay enabled in the test context)
| Test | Expectation |
|---|---|
| `createdOrderIsPublished` | `POST` → one record on `orderflow.order.events`, key = order ID, headers and payload as specified |
| `paymentModuleStoresTheOrder` | row in `payment.orders_awaiting_payment` with the order's currency and total |
| `rejectedOrderPublishesNothing` | invalid `POST` (`400`) → no record within 5 s, no outbox row |
| `rolledBackOrderPublishesNothing` | `CreateOrderUseCase.create` inside a transaction that rolls back → no outbox row |
| `eventSurvivesKafkaOutage` | Kafka paused during `POST`; order stored; record arrives after unpause |
| `duplicateEventIsStoredOnce` | the same record (same `eventId`) published again → still one row |
| `schemaOwnership` | `SchemaOwnershipOracle`: no FK from `payment` to `orders` |

## Architecture traps
| Trap | Detected by |
|---|---|
| `CreateOrderService` calls a payment use case directly | `ArchitectureOracle` rules 7/8 |
| `KafkaTemplate` in `order.application` or a direct send from `order.adapter.out` without the outbox (lost/phantom events) | `ArchitectureOracle` rule 3; `rolledBackOrderPublishesNothing` / `eventSurvivesKafkaOutage` |
| Event record in `order.domain` or `order.adapter`, imported by `payment` | `ArchitectureOracle` rule 7 |
| **`payment` imports `order.application.event.OrderPlaced`**: `order` already depends on `payment`'s events, so this creates a module cycle, although rule 7 allows the import | `ArchitectureOracle` rule 9 (deliberate trap, decided 2026-10-30) |
| Payment listener without deduplication | `duplicateEventIsStoredOnce` |
| `payment` table with FK to `orders.orders`, or the listener reading `orders.orders` | `SchemaOwnershipOracle`; review R1 |
| `platform.messaging` internals (`outbox`, `inbox`) used directly | `ArchitectureOracle` rule 7 (only `platform.messaging.api`) |

## Reference solution outline
`order.application.event.OrderPlaced` record; `OrderEventPublisher` port.out implemented in `order.adapter.out.event`
with `OutboxWriter.append` (same transaction); `CreateOrderService` publishes after save; `V6` creates schema
`payment` and the table (PK `order_id`). Because of rule 9, `payment.adapter.in.event.OrderPlacedListener` reads the
JSON into its **own** message record (the topic, headers and JSON are the contract, not the Java class) and calls
`RecordOrderAwaitingPaymentUseCase`, which deduplicates through a `port.out` backed by `ProcessedEvents` (consumer
`payment`) in the use case transaction.
