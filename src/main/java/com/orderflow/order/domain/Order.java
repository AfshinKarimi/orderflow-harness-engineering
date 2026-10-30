package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** Order aggregate root. All lines share the order's currency. */
public class Order {

    private final OrderId id;
    private final String customerId;
    private final List<OrderLine> lines;
    private final OrderStatus status;
    private final Instant createdAt;

    private Order(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.lines = lines;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Order create(OrderId id, String customerId, List<OrderLine> lines, Instant createdAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidOrderException("Customer ID is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new InvalidOrderException("Order must have at least one line");
        }
        List<OrderLine> copy = List.copyOf(lines);
        Currency currency = copy.getFirst().unitPrice().currency();
        if (copy.stream().anyMatch(line -> !line.unitPrice().currency().equals(currency))) {
            throw new InvalidOrderException("All order lines must use the same currency");
        }
        return new Order(id, customerId, copy, OrderStatus.CREATED, createdAt);
    }

    public Money total() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money.zero(currency()), Money::add);
    }

    public Currency currency() {
        return lines.getFirst().unitPrice().currency();
    }

    public OrderId id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public OrderStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
