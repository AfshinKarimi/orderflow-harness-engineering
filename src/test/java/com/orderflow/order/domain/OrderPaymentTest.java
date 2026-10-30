package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Payment transitions of the Order aggregate (ADR-0002 transition table). */
class OrderPaymentTest {

    private final Order order = Order.create(OrderId.newId(), "c-1",
            List.of(new OrderLine("p-1", 1, Money.of(new BigDecimal("5.00"), "EUR"))),
            Instant.parse("2026-10-30T12:00:00Z"));

    // --- requestPayment ---

    @Test
    void requestingPaymentStartsAnAttempt() {
        PaymentAttemptId attempt = order.requestPayment();

        assertThat(attempt).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.currentPaymentAttempt()).isEqualTo(attempt);
    }

    @Test
    void repeatedRequestWhilePendingReturnsTheSameAttempt() {
        PaymentAttemptId first = order.requestPayment();

        assertThat(order.requestPayment()).isEqualTo(first);
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void requestAfterFailureStartsANewAttempt() {
        PaymentAttemptId first = order.requestPayment();
        order.markPaymentFailed(first);

        PaymentAttemptId second = order.requestPayment();

        assertThat(second).isNotEqualTo(first);
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void paidOrderCannotRequestPaymentAgain() {
        order.markPaid(order.requestPayment());

        assertThatThrownBy(order::requestPayment)
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("is already paid");
    }

    // --- markPaid ---

    @Test
    void successForCurrentAttemptMarksOrderPaid() {
        PaymentAttemptId attempt = order.requestPayment();

        assertThat(order.markPaid(attempt)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void duplicateSuccessIsANoOp() {
        PaymentAttemptId attempt = order.requestPayment();
        order.markPaid(attempt);

        assertThat(order.markPaid(attempt)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void successForAnEarlierAttemptIsIgnored() {
        PaymentAttemptId stale = order.requestPayment();
        order.markPaymentFailed(stale);
        order.requestPayment();

        assertThat(order.markPaid(stale)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void successForAnEarlierAttemptAfterFailureIsIgnored() {
        PaymentAttemptId stale = order.requestPayment();
        order.markPaymentFailed(stale);
        PaymentAttemptId current = order.requestPayment();
        order.markPaymentFailed(current);

        assertThat(order.markPaid(stale)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void successWithoutAPaymentRequestIsRejected() {
        assertThatThrownBy(() -> order.markPaid(PaymentAttemptId.newId()))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("No payment was requested");
    }

    @Test
    void successForAnAttemptThatAlreadyFailedIsRejected() {
        PaymentAttemptId attempt = order.requestPayment();
        order.markPaymentFailed(attempt);

        assertThatThrownBy(() -> order.markPaid(attempt))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("already failed and cannot have succeeded");
    }

    // --- markPaymentFailed ---

    @Test
    void failureForCurrentAttemptMarksOrderFailed() {
        PaymentAttemptId attempt = order.requestPayment();

        assertThat(order.markPaymentFailed(attempt)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void duplicateFailureIsANoOp() {
        PaymentAttemptId attempt = order.requestPayment();
        order.markPaymentFailed(attempt);

        assertThat(order.markPaymentFailed(attempt)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    void failureForAnEarlierAttemptIsIgnored() {
        PaymentAttemptId stale = order.requestPayment();
        order.markPaymentFailed(stale);
        order.requestPayment();

        assertThat(order.markPaymentFailed(stale)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    @Test
    void failureForAnEarlierAttemptOfAPaidOrderIsIgnored() {
        PaymentAttemptId stale = order.requestPayment();
        order.markPaymentFailed(stale);
        order.markPaid(order.requestPayment());

        assertThat(order.markPaymentFailed(stale)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void failureWithoutAPaymentRequestIsRejected() {
        assertThatThrownBy(() -> order.markPaymentFailed(PaymentAttemptId.newId()))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("No payment was requested");
    }

    @Test
    void failureForAnAttemptThatAlreadySucceededIsRejected() {
        PaymentAttemptId attempt = order.requestPayment();
        order.markPaid(attempt);

        assertThatThrownBy(() -> order.markPaymentFailed(attempt))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("already succeeded and cannot have failed");
    }
}
