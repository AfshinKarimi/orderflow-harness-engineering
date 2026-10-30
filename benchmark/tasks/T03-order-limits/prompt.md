Introduce two business limits for orders:

- An order may have at most 20 lines.
- The order total must not exceed 10,000.00 in the order's currency. Exactly 10,000.00 is allowed.

Requirements:
- Both limits apply to every way an order can be created in the application, not only to the REST API.
- `POST /api/v1/orders` that violates a limit: `400` with an `application/problem+json` body whose `detail` says
  which limit was exceeded. Nothing is stored.
- Requests within the limits behave exactly as before.
