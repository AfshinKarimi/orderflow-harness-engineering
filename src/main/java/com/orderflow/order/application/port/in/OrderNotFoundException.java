package com.orderflow.order.application.port.in;

import java.util.UUID;

/** Not a business rule violation (that would be a DomainException): the requested order does not exist. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " not found");
    }
}
