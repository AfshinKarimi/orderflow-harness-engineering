package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.domain.Order;

final class OrderMapper {

    private OrderMapper() {
    }

    static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(
                order.id().value(),
                order.customerId(),
                order.status().name(),
                order.currency().getCurrencyCode(),
                order.createdAt(),
                order.lines().stream()
                        .map(line -> new OrderLineJpaEmbeddable(
                                line.productId(), line.quantity(), line.unitPrice().amount()))
                        .toList());
    }
}
