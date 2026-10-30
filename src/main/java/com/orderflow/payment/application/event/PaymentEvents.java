package com.orderflow.payment.application.event;

/** Kafka contract of the payment module's integration events (ADR-0002, ADR-0003). */
public final class PaymentEvents {

    public static final String TOPIC = "orderflow.payment.events";

    /** Values of the {@code eventType} header (the event record's simple name, set by the outbox). */
    public static final String SUCCEEDED = "PaymentSucceeded";
    public static final String FAILED = "PaymentFailed";

    private PaymentEvents() {
    }
}
