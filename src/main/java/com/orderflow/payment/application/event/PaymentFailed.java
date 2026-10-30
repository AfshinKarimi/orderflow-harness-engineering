package com.orderflow.payment.application.event;

import java.util.UUID;

/** A payment attempt was declined. */
public record PaymentFailed(UUID orderId, UUID attemptId, String reason) {
}
