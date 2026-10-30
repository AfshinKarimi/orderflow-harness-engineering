Record the status history of every order.

Requirements:
- Every status change made by the application is recorded with the new status and the time of the change.
  Creating an order counts as the first entry, with status `CREATED`.
- `GET /api/v1/orders/{id}` includes
  `"statusHistory": [{"status": "CREATED", "changedAt": "<ISO-8601 timestamp>"}, ...]` in chronological order.
- A status change and its history entry are stored together: either both are saved or neither is.
- A payment result that does not change the order (for example the same event delivered twice) adds no entry.
- Orders that exist before this change get one `CREATED` entry at their creation time.
