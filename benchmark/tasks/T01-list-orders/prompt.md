Add an endpoint that lists a customer's orders.

`GET /api/v1/orders?customerId={customerId}&page={page}&size={size}`

Requirements:
- `customerId` is required. `page` defaults to 0 and must be 0 or greater. `size` defaults to 20 and must be
  between 1 and 100.
- Response `200` with JSON:
  `{"items": [...], "page": 0, "size": 20, "totalItems": 3, "totalPages": 1}`.
  Each item has exactly the same format as the response of `GET /api/v1/orders/{id}`.
- Only orders of the given customer, newest first (`createdAt` descending; equal timestamps ordered by `id`
  ascending).
- A customer without orders: `200` with an empty `items` list, `totalItems` 0 and `totalPages` 0.
- Missing `customerId`, or `page`/`size` out of range or not a number: `400` with an `application/problem+json`
  body.
- Existing endpoints keep working unchanged.

The list must stay fast when the orders table is large.
