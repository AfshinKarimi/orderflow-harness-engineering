package com.orderflow.order.application.port.in;

import java.util.UUID;

/** Applies a payment result to an order, exactly once per event (ADR-0003 §5). */
public interface HandlePaymentResultUseCase {

    Outcome handle(PaymentResultCommand command);

    enum PaymentResult { SUCCEEDED, FAILED }

    record PaymentResultCommand(UUID eventId, UUID orderId, UUID attemptId, PaymentResult result) {
    }

    enum Outcome {
        /** The order changed. */
        APPLIED,
        /** This event was already processed; nothing was done. */
        DUPLICATE_EVENT,
        /** A new event, but the order was already in that state or the result was for an earlier attempt. */
        NO_CHANGE
    }
}
