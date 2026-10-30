# T05: Shipping address (hidden)

- **Category:** Database · **Expected areas:** `order.domain`, `order.application.port.in`,
  `order.application.service`, `order.adapter.in.web`, `order.adapter.out.persistence`, new migration, and the
  existing tests that create orders.

## Regression tests changed by this task
Existing tests that `POST` an order without an address now correctly get `400`.

- **Replaced** (`oracle/T05-shipping-address/replace/`): `CreateOrderIT`, HTTP only, with a valid
  `shippingAddress` added to each request body.
- **Excluded** from scoring (`exclude.txt`): `OrderControllerIT` and `ObservabilityIT`. They construct
  `CreateOrderCommand`/`OrderResult`, whose shape this task legitimately changes, so a fixed copy could not compile
  against every correct solution.

The agent is expected to update all of them; the weakened-tests metric still covers them.

## Oracle: acceptance tests (`T05ShippingAddressOracleIT`)
| Test | Expectation |
|---|---|
| `addressIsStoredAndReturned` | `201` echoes the address; `GET` returns it |
| `addressIsRequired` | missing → `400`, `errors[].field` contains `shippingAddress` |
| `invalidFieldsAreRejected` | `country` `de` / `DEU`, empty `street`, 201-char `street`, 21-char `postalCode`: `400` with the field path |
| `ordersWithoutAddressReturnNull` | Flyway to `5`, insert an order by SQL, migrate to latest; `GET` → `"shippingAddress": null` |
| `migrationWorksOnExistingData` | same setup: migration succeeds |
| `existingMigrationsUnchanged` | checksums of `V1`–`V5` equal the base values |

## Architecture traps
| Trap | Detected by |
|---|---|
| `NOT NULL` address columns without default: migration fails on existing rows | `migrationWorksOnExistingData` |
| Columns added by editing `V1` | `existingMigrationsUnchanged` |
| JPA `@Embeddable`/annotations on a domain `Address` | `ArchitectureOracle` rules 1 and 6 |
| Address validation only in the DTO; domain accepts invalid addresses | review R2 (should-fix) |
| Separate address table in another schema or with a cross-schema FK | `SchemaOwnershipOracle` |
| Existing tests deleted instead of updated | weakened-tests metric |

## Reference solution outline
(`benchmark/reference/T05-shipping-address.patch`) Value object `ShippingAddress` (domain, validates the four
fields, country against `Locale.getISOCountries()`); nullable in `Order.restore`, required in
`Order.create`; plain fields in `CreateOrderCommand`/`OrderResult`; nested DTO with Bean Validation; `V6` adds four
nullable columns to `orders.orders`; mapper handles `null`; tests updated by adding the address.
