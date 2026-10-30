package com.orderflow.order.application.port.in;

import com.orderflow.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model returned by order use cases. Plain types only, as it is part of the module's public API. */
public record OrderResult(
        UUID id,
        String customerId,
        String status,
        String currency,
        BigDecimal totalAmount,
        List<Line> lines,
        Instant createdAt) {

    public record Line(String productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public static OrderResult from(Order order) {
        List<Line> lines = order.lines().stream()
                .map(line -> new Line(
                        line.productId(),
                        line.quantity(),
                        line.unitPrice().amount(),
                        line.lineTotal().amount()))
                .toList();
        return new OrderResult(
                order.id().value(),
                order.customerId(),
                order.status().name(),
                order.currency().getCurrencyCode(),
                order.total().amount(),
                lines,
                order.createdAt());
    }
}
