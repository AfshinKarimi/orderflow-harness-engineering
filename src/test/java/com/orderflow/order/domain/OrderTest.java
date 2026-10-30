package com.orderflow.order.domain;

import com.orderflow.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Test
    void newOrderStartsInCreatedStatus() {
        Order order = Order.create(OrderId.newId(), "c-1", List.of(line("p-1", 1, "5.00")), NOW);

        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.customerId()).isEqualTo("c-1");
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.version()).isNull();
    }

    @Test
    void restoreKeepsPersistedStatusAndVersion() {
        OrderId id = OrderId.newId();

        Order order = Order.restore(id, "c-1", List.of(line("p-1", 2, "5.00")), OrderStatus.CREATED, NOW, 3);

        assertThat(order.id()).isEqualTo(id);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.version()).isEqualTo(3L);
        assertThat(order.total()).isEqualTo(eur("10.00"));
    }

    @Test
    void restoreRejectsCorruptData() {
        assertThatThrownBy(() -> Order.restore(OrderId.newId(), "c-1", List.of(), OrderStatus.CREATED, NOW, 0))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Order must have at least one line");
    }

    @Test
    void totalIsSumOfLineTotals() {
        Order order = Order.create(OrderId.newId(), "c-1",
                List.of(line("p-1", 2, "19.99"), line("p-2", 1, "0.02")), NOW);

        assertThat(order.total()).isEqualTo(eur("40.00"));
        assertThat(order.currency().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    void linesCannotBeModifiedFromOutside() {
        List<OrderLine> lines = new ArrayList<>(List.of(line("p-1", 1, "5.00")));
        Order order = Order.create(OrderId.newId(), "c-1", lines, NOW);

        lines.add(line("p-2", 1, "1.00"));

        assertThat(order.lines()).hasSize(1);
        assertThatThrownBy(() -> order.lines().add(line("p-3", 1, "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingCustomer() {
        assertThatThrownBy(() -> Order.create(OrderId.newId(), " ", List.of(line("p-1", 1, "5.00")), NOW))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Customer ID is required");
    }

    @Test
    void rejectsOrderWithoutLines() {
        assertThatThrownBy(() -> Order.create(OrderId.newId(), "c-1", List.of(), NOW))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Order must have at least one line");
        assertThatThrownBy(() -> Order.create(OrderId.newId(), "c-1", null, NOW))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void rejectsMixedCurrencies() {
        OrderLine usdLine = new OrderLine("p-2", 1, Money.of(BigDecimal.ONE, "USD"));

        assertThatThrownBy(() -> Order.create(OrderId.newId(), "c-1", List.of(line("p-1", 1, "5.00"), usdLine), NOW))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("All order lines must use the same currency");
    }

    @Test
    void lineRejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> line("p-1", 0, "5.00"))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("Quantity must be at least 1");
        assertThatThrownBy(() -> line("p-1", -1, "5.00"))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void lineRejectsNonPositivePrice() {
        assertThatThrownBy(() -> line("p-1", 1, "0.00"))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Unit price must be positive");
        assertThatThrownBy(() -> line("p-1", 1, "-1.00"))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void lineRejectsMissingPrice() {
        assertThatThrownBy(() -> new OrderLine("p-1", 1, null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Unit price must be positive");
    }

    @Test
    void lineRejectsMissingProduct() {
        assertThatThrownBy(() -> line("", 1, "5.00"))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Product ID is required");
    }

    @Test
    void lineTotalIsUnitPriceTimesQuantity() {
        assertThat(line("p-1", 3, "1.50").lineTotal()).isEqualTo(eur("4.50"));
    }

    private static OrderLine line(String productId, int quantity, String unitPrice) {
        return new OrderLine(productId, quantity, eur(unitPrice));
    }

    private static Money eur(String amount) {
        return Money.of(new BigDecimal(amount), "EUR");
    }
}
