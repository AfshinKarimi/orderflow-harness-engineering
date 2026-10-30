# T09: `CustomerId` value object (hidden)

- **Category:** Refactoring · **Expected areas:** `order.domain`, `order.application.service`,
  `order.adapter.out.persistence` (mapping), domain/service tests (compile changes only).

## Oracle: acceptance tests (`T09CustomerIdOracleIT`, `T09CustomerIdOracleTest`)
| Test | Expectation |
|---|---|
| `orderExposesCustomerId` | reflection: `Order.customerId()` returns `com.orderflow.order.domain.CustomerId`; `new CustomerId("c-1").value()` is `c-1` |
| `customerIdValidates` | blank and 101 characters rejected; 100 accepted |
| `restContractUnchanged` | `POST`/`GET` responses and the `400` validation response are JSON-equal to golden files recorded on the base commit (ignoring generated IDs/timestamps) |
| `useCaseInterfacesUnchanged` | public method signatures of all types in `order.application.port.in` equal the base snapshot |
| `schemaUnchanged` | no Flyway version above `5` |
| regression suite | all base tests pass (domain/service tests may be adapted only where they construct or read `customerId`) |

## Architecture traps
| Trap | Detected by |
|---|---|
| `CustomerId` leaks into `CreateOrderCommand`/`OrderResult` (contract change) | `useCaseInterfacesUnchanged` |
| JSON changes (e.g. `"customerId": {"value": "c-1"}`) | `restContractUnchanged` |
| Pre-existing tests deleted, disabled, or assertions removed instead of adapted | weakened-tests metric; review R6 |
| JPA converter/annotations placed on the domain type | `ArchitectureOracle` rules 1 and 6 |
| A migration added "while at it" | `schemaUnchanged` |
| Scope creep (other value objects, renames) | scope metric; review R7 |

## Reference solution outline
`record CustomerId(String value)` with validation in the compact constructor (throwing `InvalidOrderException`);
`Order` stores and returns it; service converts from the command's `String`; mapper converts for the entity and
`OrderResult`; tests adapted mechanically; a `CustomerIdTest` for the rules.
