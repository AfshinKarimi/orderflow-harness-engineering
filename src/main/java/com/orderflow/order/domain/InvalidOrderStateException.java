package com.orderflow.order.domain;

import com.orderflow.shared.domain.DomainException;

/** A command is not allowed in the order's current status. */
public class InvalidOrderStateException extends DomainException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
