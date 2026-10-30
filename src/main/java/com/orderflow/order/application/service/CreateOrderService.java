package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.CreateOrderUseCase;
import com.orderflow.order.application.port.in.OrderResult;
import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public CreateOrderService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Override
    public OrderResult create(CreateOrderCommand command) {
        Objects.requireNonNull(command, "command");
        List<OrderLine> lines = command.lines() == null ? List.of() : command.lines().stream()
                .map(line -> new OrderLine(
                        line.productId(),
                        line.quantity(),
                        Money.of(line.unitPrice(), command.currency())))
                .toList();

        Order order = Order.create(OrderId.newId(), command.customerId(), lines, clock.instant());
        orderRepository.save(order);
        return OrderResult.from(order);
    }
}
