-- Payment workflow (ADR-0002): the current payment attempt, NULL while the order is CREATED.
ALTER TABLE orders.orders ADD COLUMN payment_attempt_id UUID;

ALTER TABLE orders.orders ADD CONSTRAINT orders_payment_attempt_matches_status
    CHECK ((status = 'CREATED') = (payment_attempt_id IS NULL));
