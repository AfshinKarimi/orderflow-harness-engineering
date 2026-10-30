package com.orderflow.order.application.service;

import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Hand-written fake for the OrderRepository port. {@code saved} records every save in order. */
class InMemoryOrderRepository implements OrderRepository {

    final List<Order> saved = new ArrayList<>();

    @Override
    public void save(Order order) {
        saved.add(order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return saved.stream().filter(order -> order.id().equals(id)).reduce((first, latest) -> latest);
    }
}
