Customers must be able to cancel an order before it is paid.

`POST /api/v1/orders/{id}/cancellation` (no request body)

Requirements:
- Order in status `CREATED` or `PAYMENT_FAILED`: the order becomes `CANCELLED`. Response `200` with the order in
  the same format as `GET /api/v1/orders/{id}`.
- Order already `CANCELLED`: `200` with the unchanged order (repeating the request is harmless).
- Order in status `PAYMENT_PENDING` or `PAID`: `409` with an `application/problem+json` body; the order is
  unchanged.
- Unknown order: `404`; an ID that is not a UUID: `400`; both with an `application/problem+json` body.
- `GET /api/v1/orders/{id}` shows the new status.
- A payment result that arrives later for a cancelled order (Kafka topic `orderflow.payment.events`) must not
  change the order.
