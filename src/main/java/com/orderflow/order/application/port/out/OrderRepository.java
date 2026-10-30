package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;

import java.util.Optional;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(OrderId id);
}
