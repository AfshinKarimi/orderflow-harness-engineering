package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.domain.PaymentAttemptId;
import com.orderflow.shared.domain.Money;

final class OrderMapper {

    private OrderMapper() {
    }

    static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(
                order.id().value(),
                order.customerId(),
                order.status().name(),
                order.currentPaymentAttempt() == null ? null : order.currentPaymentAttempt().value(),
                order.currency().getCurrencyCode(),
                order.createdAt(),
                order.version(),
                order.lines().stream()
                        .map(line -> new OrderLineJpaEmbeddable(
                                line.productId(), line.quantity(), line.unitPrice().amount()))
                        .toList());
    }

    static Order toDomain(OrderJpaEntity entity) {
        return Order.restore(
                new OrderId(entity.getId()),
                entity.getCustomerId(),
                entity.getLines().stream()
                        .map(line -> new OrderLine(
                                line.getProductId(),
                                line.getQuantity(),
                                Money.of(line.getUnitPrice(), entity.getCurrency())))
                        .toList(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getPaymentAttemptId() == null ? null : new PaymentAttemptId(entity.getPaymentAttemptId()),
                entity.getCreatedAt(),
                entity.getVersion());
    }
}
