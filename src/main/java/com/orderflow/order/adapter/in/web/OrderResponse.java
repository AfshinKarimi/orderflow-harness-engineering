package com.orderflow.order.adapter.in.web;

import com.orderflow.order.application.port.in.OrderResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OrderResponse(
        UUID id,
        String customerId,
        String status,
        String currency,
        BigDecimal totalAmount,
        List<LineResponse> lines,
        Instant createdAt) {

    record LineResponse(String productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    static OrderResponse from(OrderResult result) {
        return new OrderResponse(
                result.id(),
                result.customerId(),
                result.status(),
                result.currency(),
                result.totalAmount(),
                result.lines().stream()
                        .map(line -> new LineResponse(
                                line.productId(), line.quantity(), line.unitPrice(), line.lineTotal()))
                        .toList(),
                result.createdAt());
    }
}
