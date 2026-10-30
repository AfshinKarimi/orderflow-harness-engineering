package com.orderflow.shared.domain;

/** Base type for business rule violations; adapters map it to a client error. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
