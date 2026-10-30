package com.orderflow.order.application.port.out;

/**
 * Business metrics of the order module. Called inside the use case transaction; implementations must count
 * only work that commits, so rolled-back and retried attempts are never counted twice.
 * Duplicate and no-op payment results are not reported.
 */
public interface OrderMetrics {

    void orderCreated();

    void paymentSucceeded();

    void paymentFailed();
}
