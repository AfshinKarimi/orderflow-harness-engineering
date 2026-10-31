Refactoring: introduce a `CustomerId` value object in the order domain.

Requirements:
- The order domain represents the customer as `com.orderflow.order.domain.CustomerId` instead of a `String`:
  `Order.customerId()` returns a `CustomerId`. It is created with `new CustomerId(String value)` (a record is fine)
  and exposes `value()`.
- Customer ID validation moves into `CustomerId`: not blank, and at most 100 characters (the size of the database
  column).
- No other behaviour changes: the REST API and its JSON (including error responses), the database schema, and the
  application's use case interfaces stay exactly as they are.
- All existing tests must still pass.
