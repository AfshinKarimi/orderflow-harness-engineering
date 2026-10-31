-- Order module owns the "orders" schema (ADR-0001: one schema per module, no cross-module FKs).
CREATE SCHEMA IF NOT EXISTS orders;

CREATE TABLE orders.orders (
    id          UUID         PRIMARY KEY,
    customer_id VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL
);

CREATE TABLE orders.order_lines (
    order_id   UUID          NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    line_no    INT           NOT NULL,
    product_id VARCHAR(100)  NOT NULL,
    quantity   INT           NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 4) NOT NULL CHECK (unit_price > 0),
    PRIMARY KEY (order_id, line_no)
);
