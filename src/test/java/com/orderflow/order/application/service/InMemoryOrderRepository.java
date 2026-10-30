package com.orderflow.order.application.service;

import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written fake for the OrderRepository port. {@code saved} records every save call in order (and may be
 * cleared by tests); the stored orders are kept separately, so clearing the record does not delete them.
 */
class InMemoryOrderRepository implements OrderRepository {

    final List<Order> saved = new ArrayList<>();
    private final Map<OrderId, Order> store = new HashMap<>();

    @Override
    public void save(Order order) {
        saved.add(order);
        store.put(order.id(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return Optional.ofNullable(store.get(id));
    }
}
