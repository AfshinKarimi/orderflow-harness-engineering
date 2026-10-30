# T09: `CustomerId` value object (hidden)

- **Category:** Refactoring · **Expected areas:** `order.domain`, `order.application.service`,
  `order.adapter.out.persistence` (mapping), domain/service tests (compile changes only).

## Oracle
| Class | Kind | Expectation |
|---|---|---|
| `T09CustomerIdOracleTest` | acceptance (reflection only, compiles on any solution) | `Order.customerId()` returns `com.orderflow.order.domain.CustomerId`; `new CustomerId("c-1").value()` is `c-1`, equal values are equal; blank, `null` and 101 characters rejected, 100 accepted |
| `T09ContractGuardTest` | guard | public signatures of all types in `order.application.port.in` equal the snapshot `resources/benchmark/t09-port-in-signatures.txt`, generated from the base commit |
| `T09ContractGuardIT` | guard | `POST`/`GET` JSON (except `id`, `createdAt`) and the `400` validation responses for a missing and a 101-character `customerId` are exactly the base's; Flyway versions are exactly `1`-`5` |
| regression suite | | all base tests pass (domain/service tests may be adapted only where they construct or read `customerId`) |

Proven: adding a default method to `GetOrderUseCase` makes `T09ContractGuardTest` fail.

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
(`benchmark/reference/T09-customer-id.patch`) `record CustomerId(String value)` with validation in the compact
constructor (throwing `InvalidOrderException`, same message as before for blank values);
`Order` stores and returns it; service converts from the command's `String`; mapper converts for the entity and
`OrderResult`; tests adapted mechanically; a `CustomerIdTest` for the rules.
