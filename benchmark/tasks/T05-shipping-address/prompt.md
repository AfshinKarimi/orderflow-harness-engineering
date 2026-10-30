Orders need a shipping address.

Requirements:
- `POST /api/v1/orders` requires a `shippingAddress` object:
  `{"street": "...", "city": "...", "postalCode": "...", "country": "DE"}`.
  `street` 1–200 characters, `city` 1–100, `postalCode` 1–20, `country` an ISO 3166-1 alpha-2 code in upper case.
- Missing or invalid address: `400` with an `application/problem+json` body that lists the field errors in the same
  format as the existing validation errors (for example field `shippingAddress.country`).
- The responses of `POST /api/v1/orders` and `GET /api/v1/orders/{id}` include `shippingAddress` with the same four
  fields.
- The address is stored in the database. Orders created before this change have no address; `GET` returns
  `"shippingAddress": null` for them.
- The database change must work on a database that already contains orders.
