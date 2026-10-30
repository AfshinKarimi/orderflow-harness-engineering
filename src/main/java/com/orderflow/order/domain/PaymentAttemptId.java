package com.orderflow.order.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies one payment attempt of an order; the idempotency key across order and payment (ADR-0002). */
public record PaymentAttemptId(UUID value) {

    public PaymentAttemptId {
        Objects.requireNonNull(value, "value");
    }

    public static PaymentAttemptId newId() {
        return new PaymentAttemptId(UUID.randomUUID());
    }
}
