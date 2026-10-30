Make the error responses of the order API uniform and safe.

All error responses of `/api/v1/orders` endpoints are `application/problem+json` (RFC 9457) with `title`, `status`
and `detail` (and `type`, which may be omitted when it is `about:blank`), and a `correlationId` property equal to
the `X-Correlation-Id` response header.

Requirements:
- Unknown order (`GET /api/v1/orders/{id}`): `404`, title `Order not found`, plus a property `orderId`.
- Malformed JSON or wrong JSON value types in the `POST /api/v1/orders` body: `400`, title `Malformed request`;
  the `detail` must not contain Java class names or parser internals.
- Request without `Content-Type: application/json`: `415`. Unsupported HTTP method: `405`.
- Unexpected failures, including an unavailable database: `500`, title `Internal error`, detail
  `An unexpected error occurred.`. The body must not contain exception messages, class names, SQL or stack
  traces. The failure itself is logged at `ERROR` level together with the correlation ID.
- Existing validation errors (`400` with `errors`) and business rule violations keep their status codes and
  fields, and also get `correlationId`.
