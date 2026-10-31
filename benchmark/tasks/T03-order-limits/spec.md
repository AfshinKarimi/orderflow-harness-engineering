# T03: Order size and amount limits (hidden)

- **Category:** Business rule · **Expected areas:** `order.domain` (and its tests); possibly the web error
  mapping. No migration expected.

## Oracle: acceptance tests (`T03OrderLimitsOracleIT`)
| Test | Expectation |
|---|---|
| `twentyLinesAreAccepted` / `twentyOneLinesAreRejected` | `201` / `400` problem+json, `detail` contains `20`; no row for the customer |
| `totalOfExactlyTenThousandIsAccepted` | e.g. 1 × 10000.00 → `201`; 4 × 2500.0000 → `201` (scale must not matter) |
| `totalAboveTenThousandIsRejected` | 1 × 10000.01 → `400`, `detail` mentions the amount limit; nothing stored |
| `limitsApplyOutsideTheRestApi` | `CreateOrderUseCase.create` (existing public use case interface) with 21 lines or 10000.01 throws, and no row is stored |

## Architecture traps
| Trap | Detected by |
|---|---|
| Limits only as Bean Validation on the request DTO (`@Size(max = 20)`): the domain stays unprotected | `limitsApplyOutsideTheRestApi`; review R2 |
| `BigDecimal.equals`/scale-sensitive comparison (`10000.00` vs `10000.0000`) | `totalOfExactlyTenThousandIsAccepted` |
| Limit checked in the service or controller instead of the aggregate | review R2 (should-fix if service, must-fix if controller) |
| Magic numbers duplicated across layers | review R8 |
| Missing domain tests for the boundary (20/21, 10000.00/10000.01) | review R6 |

## Reference solution outline
Two invariants in `Order.create` (and `restore` only if consistent with existing data: it must not break loading
old orders, so `restore` should not enforce them) throwing `InvalidOrderException`; comparison with
`compareTo` on `Money`; boundary tests in `OrderTest`; no change to the controller needed (existing `400` mapping).
