package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.OrderNotFoundException;
import com.orderflow.order.application.port.in.OrderResult;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetOrderServiceTest {

    private final InMemoryOrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService service = new GetOrderService(repository);

    @Test
    void returnsExistingOrder() {
        Order order = Order.create(OrderId.newId(), "c-1",
                List.of(new OrderLine("p-1", 2, Money.of(new BigDecimal("1.50"), "EUR"))),
                Instant.parse("2026-10-30T12:00:00Z"));
        repository.save(order);

        OrderResult result = service.get(order.id().value());

        assertThat(result).isEqualTo(OrderResult.from(order));
    }

    @Test
    void throwsWhenOrderDoesNotExist() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(unknown))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessage("Order " + unknown + " not found");
    }
}
