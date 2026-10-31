# T07: Payment fee quote (hidden)

- **Category:** Cross-module dependency · **Expected areas:** `payment.domain`, `payment.application.*`,
  `payment.adapter.in.web`; no changes in `order` except possibly its `port.in`.

## Oracle: acceptance tests (`T07PaymentFeeOracleIT`)
Orders created via `POST /api/v1/orders`.

| Test | Total | Expected fee |
|---|---|---|
| `onePointFivePercent` | 120.00 | 1.80 |
| `roundsHalfUp` | 103.00 | 1.55 (half-even would give 1.54) |
| `roundsToTwoDecimals` | 1234.57 | 18.52 |
| `minimumFee` | 10.00 | 0.50 |
| `currencyAndTotalAreReturned` | 120.00 in `USD` | `currency` `USD`, `orderTotal` 120.00 |
| `unknownOrder` | random UUID | `404` problem+json |
| `invalidOrderId` | missing / `abc` | `400` problem+json |

## Architecture traps
| Trap | Detected by |
|---|---|
| `payment` imports `order.domain`, `order.application.service`, `order.application.port.out` or `order.adapter` | `ArchitectureOracle` rule 7 |
| `payment.application`/`payment.domain` calls `order.application.port.in` (cross-module call outside `adapter.in`) | `ArchitectureOracle` rule 8 |
| `payment` reads `orders.orders` via SQL/JPA | review R1 (must-fix; rule 11, not visible to ArchUnit) |
| Fee computed in the controller | review R2 |
| `Money` arithmetic producing more than 2 decimals, or `HALF_EVEN` | acceptance tests |
| `payment.adapter.out` calling the order module | `ArchitectureOracle` rule 8 (only `adapter.in` may) |
| **`payment` calling `order.application.port.in.GetOrderUseCase` at all**: `order` already depends on `payment` (it consumes `payment.application.event`), so this creates a module cycle. It is the most natural solution and passes rules 7/8 | `ArchitectureOracle` rule 9 (decided 2026-10-30: rules stay, this is a deliberate trap; see architecture.md open points) |

## Reference solution outline
Dependencies may only point from `order` to `payment` (rule 9). `payment.domain.FeePolicy` (1.5 %, half-up,
minimum 0.50) with unit tests; `QuoteFeeUseCase` in `payment.application.port.in` taking the total and currency as
plain values. The HTTP endpoint is served by `order.adapter.in.web`: it reads the order through its own
`GetOrderUseCase` and calls `payment`'s `QuoteFeeUseCase` (public API, from `adapter.in`: rules 7/8 hold, no cycle).
Raising the conflict instead of choosing this design is equally acceptable in review.
(`benchmark/reference/T07-payment-fee.patch`.) Proven: the same controller placed in `payment.adapter.in.web`
fails only `rule9_noCyclesBetweenModules`.
