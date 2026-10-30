package com.orderflow.order.adapter.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(schema = "orders", name = "orders")
class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    @ElementCollection
    @CollectionTable(schema = "orders", name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_no")
    private List<OrderLineJpaEmbeddable> lines = new ArrayList<>();

    protected OrderJpaEntity() {
    }

    /** {@code version} is null for a new order; otherwise the version it was loaded with (optimistic lock check). */
    OrderJpaEntity(UUID id, String customerId, String status, UUID paymentAttemptId, String currency,
                   Instant createdAt, Long version, List<OrderLineJpaEmbeddable> lines) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.paymentAttemptId = paymentAttemptId;
        this.currency = currency;
        this.createdAt = createdAt;
        this.version = version;
        this.lines = new ArrayList<>(lines);
    }

    UUID getId() {
        return id;
    }

    String getCustomerId() {
        return customerId;
    }

    String getStatus() {
        return status;
    }

    UUID getPaymentAttemptId() {
        return paymentAttemptId;
    }

    String getCurrency() {
        return currency;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Long getVersion() {
        return version;
    }

    List<OrderLineJpaEmbeddable> getLines() {
        return lines;
    }
}
