package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Order aggregate root. All lines share the order's currency.
 * {@code version} supports optimistic locking; it is {@code null} until the order is first persisted.
 */
public class Order {

    private final OrderId id;
    private final String customerId;
    private final List<OrderLine> lines;
    private final OrderStatus status;
    private final Instant createdAt;
    private final Long version;

    private Order(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, Instant createdAt,
                  Long version) {
        this.id = id;
        this.customerId = customerId;
        this.lines = lines;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    public static Order create(OrderId id, String customerId, List<OrderLine> lines, Instant createdAt) {
        return new Order(Objects.requireNonNull(id, "id"), requireCustomer(customerId), requireLines(lines),
                OrderStatus.CREATED, Objects.requireNonNull(createdAt, "createdAt"), null);
    }

    /** Rebuilds a persisted order. Invariants are checked again, so corrupt data fails loudly. */
    public static Order restore(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status,
                                Instant createdAt, long version) {
        return new Order(Objects.requireNonNull(id, "id"), requireCustomer(customerId), requireLines(lines),
                Objects.requireNonNull(status, "status"), Objects.requireNonNull(createdAt, "createdAt"), version);
    }

    private static String requireCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidOrderException("Customer ID is required");
        }
        return customerId;
    }

    private static List<OrderLine> requireLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new InvalidOrderException("Order must have at least one line");
        }
        List<OrderLine> copy = List.copyOf(lines);
        Currency currency = copy.getFirst().unitPrice().currency();
        if (copy.stream().anyMatch(line -> !line.unitPrice().currency().equals(currency))) {
            throw new InvalidOrderException("All order lines must use the same currency");
        }
        return copy;
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

    public Long version() {
        return version;
    }
}
