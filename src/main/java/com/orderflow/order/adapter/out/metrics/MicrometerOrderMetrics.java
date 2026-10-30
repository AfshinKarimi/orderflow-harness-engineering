package com.orderflow.order.adapter.out.metrics;

import com.orderflow.order.application.port.out.OrderMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts only after the surrounding transaction committed; a rollback discards the increment.
 * Tags have a fixed set of values: never IDs, amounts or messages (docs/operations/observability.md).
 */
@Component
class MicrometerOrderMetrics implements OrderMetrics {

    private final Counter ordersPlaced;
    private final Counter paymentsSucceeded;
    private final Counter paymentsFailed;

    MicrometerOrderMetrics(MeterRegistry registry) {
        ordersPlaced = Counter.builder("orderflow.orders.placed")
                .description("Orders placed (committed)")
                .register(registry);
        paymentsSucceeded = paymentResults(registry, "succeeded");
        paymentsFailed = paymentResults(registry, "failed");
    }

    private static Counter paymentResults(MeterRegistry registry, String result) {
        return Counter.builder("orderflow.orders.payment.results")
                .description("Payment results applied to orders (committed; duplicates and no-ops excluded)")
                .tag("result", result)
                .register(registry);
    }

    @Override
    public void orderCreated() {
        afterCommit(ordersPlaced);
    }

    @Override
    public void paymentSucceeded() {
        afterCommit(paymentsSucceeded);
    }

    @Override
    public void paymentFailed() {
        afterCommit(paymentsFailed);
    }

    private static void afterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
