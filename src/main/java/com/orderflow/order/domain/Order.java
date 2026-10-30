package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Order aggregate root. All lines share the order's currency.
 * Payment transitions follow ADR-0002: {@code CREATED → PAYMENT_PENDING → PAID}, and
 * {@code PAYMENT_PENDING → PAYMENT_FAILED → PAYMENT_PENDING} (retry with a new attempt).
 * {@code version} supports optimistic locking; it is {@code null} until the order is first persisted.
 */
public class Order {

    private final OrderId id;
    private final String customerId;
    private final List<OrderLine> lines;
    private final Instant createdAt;
    private final Long version;
    private OrderStatus status;
    private PaymentAttemptId currentPaymentAttempt;

    private Order(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status,
                  PaymentAttemptId currentPaymentAttempt, Instant createdAt, Long version) {
        this.id = id;
        this.customerId = customerId;
        this.lines = lines;
        this.status = status;
        this.currentPaymentAttempt = currentPaymentAttempt;
        this.createdAt = createdAt;
        this.version = version;
    }

    public static Order create(OrderId id, String customerId, List<OrderLine> lines, Instant createdAt) {
        return new Order(Objects.requireNonNull(id, "id"), requireCustomer(customerId), requireLines(lines),
                OrderStatus.CREATED, null, Objects.requireNonNull(createdAt, "createdAt"), null);
    }

    /** Rebuilds a persisted order. Invariants are checked again, so corrupt data fails loudly. */
    public static Order restore(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status,
                                PaymentAttemptId currentPaymentAttempt, Instant createdAt, long version) {
        Objects.requireNonNull(status, "status");
        if ((status == OrderStatus.CREATED) != (currentPaymentAttempt == null)) {
            throw new InvalidOrderException(
                    "Order in status " + status + " must " + (status == OrderStatus.CREATED ? "not " : "")
                            + "have a payment attempt");
        }
        return new Order(Objects.requireNonNull(id, "id"), requireCustomer(customerId), requireLines(lines),
                status, currentPaymentAttempt, Objects.requireNonNull(createdAt, "createdAt"), version);
    }

    /**
     * Starts a payment attempt. Idempotent while pending: a repeated request returns the current attempt.
     *
     * @throws InvalidOrderStateException if the order is already paid
     */
    public PaymentAttemptId requestPayment() {
        return switch (status) {
            case PAYMENT_PENDING -> currentPaymentAttempt;
            case CREATED, PAYMENT_FAILED -> {
                currentPaymentAttempt = PaymentAttemptId.newId();
                status = OrderStatus.PAYMENT_PENDING;
                yield currentPaymentAttempt;
            }
            case PAID -> throw new InvalidOrderStateException("Order " + id.value() + " is already paid");
        };
    }

    /**
     * Applies a successful payment result. Results for an earlier attempt are stale and ignored.
     *
     * @return {@code true} if the order changed; {@code false} for a duplicate or a stale attempt
     * @throws InvalidOrderStateException if no payment was requested, or this attempt was already reported as failed
     */
    public boolean markPaid(PaymentAttemptId attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        return switch (status) {
            case CREATED -> throw noPaymentRequested();
            case PAYMENT_PENDING -> {
                if (!attemptId.equals(currentPaymentAttempt)) {
                    yield false;
                }
                status = OrderStatus.PAID;
                yield true;
            }
            case PAID -> false;
            case PAYMENT_FAILED -> {
                if (!attemptId.equals(currentPaymentAttempt)) {
                    yield false;
                }
                throw contradictoryResult(attemptId, "failed", "succeeded");
            }
        };
    }

    /**
     * Applies a failed payment result. Results for an earlier attempt are stale and ignored.
     *
     * @return {@code true} if the order changed; {@code false} for a duplicate or a stale attempt
     * @throws InvalidOrderStateException if no payment was requested, or this attempt was already reported as paid
     */
    public boolean markPaymentFailed(PaymentAttemptId attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        return switch (status) {
            case CREATED -> throw noPaymentRequested();
            case PAYMENT_PENDING -> {
                if (!attemptId.equals(currentPaymentAttempt)) {
                    yield false;
                }
                status = OrderStatus.PAYMENT_FAILED;
                yield true;
            }
            case PAYMENT_FAILED -> false;
            case PAID -> {
                if (!attemptId.equals(currentPaymentAttempt)) {
                    yield false;
                }
                throw contradictoryResult(attemptId, "succeeded", "failed");
            }
        };
    }

    private InvalidOrderStateException noPaymentRequested() {
        return new InvalidOrderStateException("No payment was requested for order " + id.value());
    }

    private InvalidOrderStateException contradictoryResult(PaymentAttemptId attemptId, String before, String now) {
        return new InvalidOrderStateException("Payment attempt " + attemptId.value() + " of order " + id.value()
                + " already " + before + " and cannot have " + now);
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

    /** {@code null} while the order is {@code CREATED}. */
    public PaymentAttemptId currentPaymentAttempt() {
        return currentPaymentAttempt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
