package com.orderflow.payment.application.event;

import java.util.UUID;

/** A payment attempt was approved. {@code attemptId} identifies the order's payment attempt. */
public record PaymentSucceeded(UUID orderId, UUID attemptId) {
}
