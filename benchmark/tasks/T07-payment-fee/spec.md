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

## Reference solution outline
`payment.domain.FeePolicy` (1.5 %, half-up, minimum 0.50) with unit tests; `QuoteFeeUseCase` in
`payment.application.port.in` taking the total and currency as plain values; `payment.adapter.in.web` controller
gets the order through `order.application.port.in.GetOrderUseCase` (allowed: public API from `adapter.in`), then
calls `QuoteFeeUseCase`; `OrderNotFoundException` mapped to `404` in the payment module's handler.
