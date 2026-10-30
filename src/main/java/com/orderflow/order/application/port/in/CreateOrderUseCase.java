package com.orderflow.order.application.port.in;

public interface CreateOrderUseCase {

    OrderResult create(CreateOrderCommand command);
}
