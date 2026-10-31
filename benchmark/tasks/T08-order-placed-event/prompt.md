When an order is created, the order module must announce it, and the payment module must keep its own list of
orders awaiting payment.

Requirements:
- Every created order publishes one event to Kafka topic `orderflow.order.events`: record key = order ID,
  header `eventType` = `OrderPlaced`, header `eventId` = a unique event ID, JSON payload
  `{"orderId": "...", "customerId": "...", "currency": "EUR", "totalAmount": 39.98, "placedAt": "<ISO-8601>"}`.
- The event is published if and only if the order was stored: no event for a rejected or rolled-back order, and
  no lost event when Kafka is temporarily unavailable. The application already has a transactional outbox for
  this.
- The payment module consumes the event and stores one row in table `payment.orders_awaiting_payment`
  (`order_id`, `currency`, `total_amount`, `placed_at`, `received_at`). The same event delivered twice results in
  one row.
- The payment module must not read the order module's tables, and the order module must not call the payment
  module directly.
