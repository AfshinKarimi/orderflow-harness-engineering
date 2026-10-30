package com.orderflow.order.domain;

import com.orderflow.shared.domain.DomainException;

public class InvalidOrderException extends DomainException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
