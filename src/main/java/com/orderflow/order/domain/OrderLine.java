package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;

public record OrderLine(String productId, int quantity, Money unitPrice) {

    public OrderLine {
        if (productId == null || productId.isBlank()) {
            throw new InvalidOrderException("Product ID is required");
        }
        if (quantity < 1) {
            throw new InvalidOrderException("Quantity must be at least 1, was " + quantity);
        }
        if (unitPrice == null || !unitPrice.isPositive()) {
            throw new InvalidOrderException("Unit price must be positive");
        }
    }

    public Money lineTotal() {
        return unitPrice.multiply(quantity);
    }
}
