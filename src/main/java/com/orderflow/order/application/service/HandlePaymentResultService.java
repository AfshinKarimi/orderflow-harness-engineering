package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.HandlePaymentResultUseCase;
import com.orderflow.order.application.port.in.OrderNotFoundException;
import com.orderflow.order.application.port.out.OrderMetrics;
import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.application.port.out.ProcessedEventStore;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.PaymentAttemptId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction covers the deduplication record and the order change: either both commit or neither does,
 * so a duplicate is always skipped and a failed attempt can always be retried (ADR-0003 §5).
 */
@Service
@Transactional
public class HandlePaymentResultService implements HandlePaymentResultUseCase {

    private final ProcessedEventStore processedEvents;
    private final OrderRepository orderRepository;
    private final OrderMetrics metrics;

    public HandlePaymentResultService(ProcessedEventStore processedEvents, OrderRepository orderRepository,
                                      OrderMetrics metrics) {
        this.processedEvents = processedEvents;
        this.orderRepository = orderRepository;
        this.metrics = metrics;
    }

    @Override
    public Outcome handle(PaymentResultCommand command) {
        if (!processedEvents.markProcessed(command.eventId())) {
            return Outcome.DUPLICATE_EVENT;
        }
        Order order = orderRepository.findById(new OrderId(command.orderId()))
                .orElseThrow(() -> new OrderNotFoundException(command.orderId()));
        PaymentAttemptId attempt = new PaymentAttemptId(command.attemptId());
        boolean changed = switch (command.result()) {
            case SUCCEEDED -> order.markPaid(attempt);
            case FAILED -> order.markPaymentFailed(attempt);
        };
        if (!changed) {
            return Outcome.NO_CHANGE;
        }
        orderRepository.save(order);
        switch (command.result()) {
            case SUCCEEDED -> metrics.paymentSucceeded();
            case FAILED -> metrics.paymentFailed();
        }
        return Outcome.APPLIED;
    }
}
