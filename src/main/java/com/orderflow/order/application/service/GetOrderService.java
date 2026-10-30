package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.GetOrderUseCase;
import com.orderflow.order.application.port.in.OrderNotFoundException;
import com.orderflow.order.application.port.in.OrderResult;
import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.OrderId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetOrderService implements GetOrderUseCase {

    private final OrderRepository orderRepository;

    public GetOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public OrderResult get(UUID orderId) {
        return orderRepository.findById(new OrderId(orderId))
                .map(OrderResult::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
