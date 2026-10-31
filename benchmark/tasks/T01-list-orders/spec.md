# T01: List a customer's orders (hidden)

- **Category:** REST API · **Expected areas:** `order.adapter.in.web`, `order.application.port.in`,
  `order.application.port.out`, `order.application.service`, `order.adapter.out.persistence`, new migration (index).

## Oracle: acceptance tests (`T01ListOrdersOracleIT`)
Setup via `POST /api/v1/orders` only.

| Test | Expectation |
|---|---|
| `listsOnlyTheCustomersOrdersNewestFirst` | 3 orders for `c-1`, 2 for `c-2`; `c-1` returns its 3, `createdAt` descending |
| `pagesThroughOrders` | 5 orders, `size=2`: pages 0/1/2 hold 2/2/1 items, `totalItems` 5, `totalPages` 3; page 3 is empty |
| `defaultsPageAndSize` | no `page`/`size`: `page` 0, `size` 20 |
| `customerWithoutOrders` | `200`, `items` empty, `totalItems` 0, `totalPages` 0 |
| `itemsHaveTheSameFormatAsGetById` | each item equals the `GET /api/v1/orders/{id}` JSON for that order |
| `rejectsInvalidParameters` | missing `customerId`, `size=0`, `size=101`, `page=-1`, `size=abc`: `400`, `application/problem+json` |
| `existingEndpointsUnchanged` | regression suite |

## Architecture traps
| Trap | Detected by |
|---|---|
| Controller injects the Spring Data repository or the persistence adapter | `ArchitectureOracle` rule 4/5 |
| `Page`/`Pageable` (Spring Data) in `application.port.in` or `service` | `ArchitectureOracle` rule 3 |
| JPA entity returned in the response | `ArchitectureOracle` rule 5 (`in.web` → `out.persistence`) |
| All orders loaded and filtered/paged in memory | review R1 (must-fix: "fast when large") |
| No index on `customer_id`, or an edited `V1` | review R4 (should-fix) / migration checksum |

## Reference solution outline
`ListOrdersUseCase` in `port.in` with a plain query record (`customerId`, `page`, `size`) and a result record
(`items` as `OrderResult`, totals); `OrderRepository.findByCustomer(customerId, offset, limit)` and
`countByCustomer`; persistence adapter with a Spring Data query ordered by `createdAt desc, id asc`; parameter
validation in the controller (`@Validated`, `@Min`/`@Max`) mapped to problem+json; `V6` index on
`orders.orders (customer_id, created_at DESC)`. Tests: service unit test with fake, `@WebMvcTest`, persistence IT.
