The payment module must quote the payment fee for an order.

`GET /api/v1/payments/fees?orderId={orderId}`

Requirements:
- Response `200` with JSON `{"orderId": "...", "currency": "EUR", "orderTotal": 120.00, "fee": 1.80}`.
- The fee is 1.5 % of the order total, rounded half-up to 2 decimal places, and at least 0.50 in the order's
  currency.
- Unknown order: `404`; missing `orderId` or not a UUID: `400`; both with an `application/problem+json` body.
- Order data belongs to the order module, which remains the only place that reads or stores it.
