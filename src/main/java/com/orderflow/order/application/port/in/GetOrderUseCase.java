package com.orderflow.order.application.port.in;

import java.util.UUID;

public interface GetOrderUseCase {

    /** @throws OrderNotFoundException if no order has this ID */
    OrderResult get(UUID orderId);
}
