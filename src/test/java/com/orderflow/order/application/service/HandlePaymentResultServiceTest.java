package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.Outcome;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.PaymentResult;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.PaymentResultCommand;
import com.orderflow.order.application.port.in.OrderNotFoundException;
import com.orderflow.order.application.port.out.ProcessedEventStore;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.domain.PaymentAttemptId;
import com.orderflow.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandlePaymentResultServiceTest {

    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final FakeProcessedEventStore processedEvents = new FakeProcessedEventStore();
    private final RecordingOrderMetrics metrics = new RecordingOrderMetrics();
    private final HandlePaymentResultService service =
            new HandlePaymentResultService(processedEvents, orders, metrics);

    private Order order;
    private PaymentAttemptId attempt;

    @BeforeEach
    void pendingOrder() {
        order = Order.create(OrderId.newId(), "c-1",
                List.of(new OrderLine("p-1", 1, Money.of(new BigDecimal("5.00"), "EUR"))),
                Instant.parse("2026-10-30T12:00:00Z"));
        attempt = order.requestPayment();
        orders.save(order);
        orders.saved.clear();
    }

    @Test
    void appliesSuccessAndSavesOrder() {
        assertThat(service.handle(command(UUID.randomUUID(), PaymentResult.SUCCEEDED))).isEqualTo(Outcome.APPLIED);

        assertThat(orders.saved).singleElement().extracting(Order::status).isEqualTo(OrderStatus.PAID);
        assertThat(metrics.recorded).containsExactly("paymentSucceeded");
    }

    @Test
    void appliesFailureAndSavesOrder() {
        assertThat(service.handle(command(UUID.randomUUID(), PaymentResult.FAILED))).isEqualTo(Outcome.APPLIED);

        assertThat(orders.saved).singleElement().extracting(Order::status).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(metrics.recorded).containsExactly("paymentFailed");
    }

    @Test
    void skipsADuplicateEventWithoutTouchingTheOrder() {
        UUID eventId = UUID.randomUUID();
        service.handle(command(eventId, PaymentResult.SUCCEEDED));
        orders.saved.clear();
        metrics.recorded.clear();

        assertThat(service.handle(command(eventId, PaymentResult.SUCCEEDED))).isEqualTo(Outcome.DUPLICATE_EVENT);

        assertThat(orders.saved).isEmpty();
        assertThat(metrics.recorded).as("a duplicate is not counted again").isEmpty();
    }

    @Test
    void newEventWithAnAlreadyAppliedResultChangesNothing() {
        service.handle(command(UUID.randomUUID(), PaymentResult.SUCCEEDED));
        orders.saved.clear();
        metrics.recorded.clear();

        assertThat(service.handle(command(UUID.randomUUID(), PaymentResult.SUCCEEDED))).isEqualTo(Outcome.NO_CHANGE);

        assertThat(orders.saved).isEmpty();
        assertThat(metrics.recorded).isEmpty();
    }

    @Test
    void unknownOrderIsAnError() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.handle(new PaymentResultCommand(
                UUID.randomUUID(), unknown, attempt.value(), PaymentResult.SUCCEEDED)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    private PaymentResultCommand command(UUID eventId, PaymentResult result) {
        return new PaymentResultCommand(eventId, order.id().value(), attempt.value(), result);
    }

    /** Same contract as the real store, without transactions (those are proven in PaymentResultsListenerIT). */
    private static final class FakeProcessedEventStore implements ProcessedEventStore {

        private final Set<UUID> processed = new HashSet<>();

        @Override
        public boolean markProcessed(UUID eventId) {
            return processed.add(eventId);
        }
    }
}
