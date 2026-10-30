package com.orderflow.order.adapter.out.persistence;

import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import org.springframework.stereotype.Component;

@Component
class OrderPersistenceAdapter implements OrderRepository {

    private final SpringDataOrderRepository repository;

    OrderPersistenceAdapter(SpringDataOrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Order order) {
        repository.save(OrderMapper.toEntity(order));
    }
}
