package com.orderflow.order.application.port.out;

import com.orderflow.order.domain.Order;

public interface OrderRepository {

    void save(Order order);
}
