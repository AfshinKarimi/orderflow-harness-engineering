Add an endpoint to request payment for an order, with a limit on payment attempts.

`POST /api/v1/orders/{id}/payment-requests` (no request body)

Requirements:
- Order in status `CREATED` or `PAYMENT_FAILED`: a new payment attempt starts and the order becomes
  `PAYMENT_PENDING`. Response `202` with JSON `{"orderId": "...", "attemptId": "...", "attemptNumber": 1}`.
- Order already `PAYMENT_PENDING`: `202` with the current attempt (same `attemptId` and `attemptNumber`); no new
  attempt is started.
- Order `PAID`: `409` with an `application/problem+json` body.
- An order has at most 3 payment attempts. After the third attempt has failed, a further request is rejected with
  `409` and an `application/problem+json` body, and the order stays `PAYMENT_FAILED`.
- Unknown order: `404`; an ID that is not a UUID: `400`; both with an `application/problem+json` body.
- The number of attempts must survive an application restart. Existing orders that already have a payment
  attempt count it as their first.

Payment results arrive as today through Kafka topic `orderflow.payment.events` (`PaymentSucceeded` /
`PaymentFailed`, identified by `attemptId`).
