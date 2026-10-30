package com.orderflow.order.domain;

/** Transitions are defined in Order and ADR-0002. */
public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED
}
