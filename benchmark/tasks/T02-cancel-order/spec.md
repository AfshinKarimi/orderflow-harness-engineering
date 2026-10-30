# T02: Cancel an order (hidden)

- **Category:** REST API + business rule · **Expected areas:** `order.domain`, `order.application.*`,
  `order.adapter.in.web`, `order.adapter.out.persistence`, new migration.

## Oracle: acceptance tests (`T02CancelOrderOracleIT`)
Orders in `PAYMENT_PENDING`/`PAID`/`PAYMENT_FAILED` are prepared by SQL (`status`, `payment_attempt_id`) plus,
for `PAID`/`PAYMENT_FAILED`, a `PaymentSucceeded`/`PaymentFailed` record published to `orderflow.payment.events`
with the existing headers (`eventId`, `eventType`).

| Test | Expectation |
|---|---|
| `cancelsACreatedOrder` | `200`, `status` `CANCELLED`; `GET` shows `CANCELLED` |
| `cancelsAnOrderWhosePaymentFailed` | `200`, `CANCELLED` |
| `cancellingTwiceIsHarmless` | second request `200`, same body |
| `rejectsPendingAndPaidOrders` | `409`, `application/problem+json`; `GET` status unchanged |
| `unknownAndInvalidIds` | `404` / `400`, problem+json |
| `latePaymentResultDoesNotChangeACancelledOrder` | cancel a `PAYMENT_FAILED` order, then publish `PaymentSucceeded` for its attempt; after the record is consumed or dead-lettered, status is still `CANCELLED` |

## Architecture traps
| Trap | Detected by |
|---|---|
| `V4` CHECK `(status = 'CREATED') = (payment_attempt_id IS NULL)` rejects cancelling a `CREATED` order. Editing `V4`, dropping the constraint without replacement, or inventing an attempt ID | migration checksum; review R4 (constraint must still encode the rule for the other statuses) |
| State rules in the controller | review R1/R2 |
| `DomainException` mapped to `400` only (no `409`), or *all* domain exceptions changed to `409` | acceptance test / regression suite (create validation must stay `400`) |
| No domain tests for each source status | review R2/R6 |

## Reference solution outline
`OrderStatus.CANCELLED`; `Order.cancel()` returning whether it changed, throwing a dedicated `DomainException`
subclass for `PAYMENT_PENDING`/`PAID`; `CancelOrderUseCase`; `409` mapping for that exception; `markPaid`/
`markPaymentFailed` treat `CANCELLED` like a stale result (no change) or reject it (dead letter), either is fine;
`V6` replaces the CHECK with one that still requires an attempt for `PAYMENT_PENDING`/`PAID`/`PAYMENT_FAILED` and
none for `CREATED`. Domain tests for every source status.
