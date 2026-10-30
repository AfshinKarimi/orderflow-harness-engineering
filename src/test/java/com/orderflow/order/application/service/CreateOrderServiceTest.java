package com.orderflow.order.application.service;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.OrderResult;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.shared.domain.InvalidMoneyException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private final InMemoryOrderRepository repository = new InMemoryOrderRepository();
    private final RecordingOrderMetrics metrics = new RecordingOrderMetrics();
    private final CreateOrderService service =
            new CreateOrderService(repository, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsAndSavesOrder() {
        OrderResult result = service.create(new CreateOrderCommand("c-1", "EUR", List.of(
                new CreateOrderCommand.Line("p-1", 2, new BigDecimal("19.99")),
                new CreateOrderCommand.Line("p-2", 1, new BigDecimal("5")))));

        assertThat(repository.saved).hasSize(1);
        assertThat(metrics.recorded).containsExactly("orderCreated");
        Order saved = repository.saved.getFirst();
        assertThat(saved.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.createdAt()).isEqualTo(NOW);

        assertThat(result.id()).isEqualTo(saved.id().value());
        assertThat(result.customerId()).isEqualTo("c-1");
        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.totalAmount()).isEqualTo(new BigDecimal("44.98"));
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.lines()).containsExactly(
                new OrderResult.Line("p-1", 2, new BigDecimal("19.99"), new BigDecimal("39.98")),
                new OrderResult.Line("p-2", 1, new BigDecimal("5.00"), new BigDecimal("5.00")));
    }

    @Test
    void generatesUniqueIds() {
        CreateOrderCommand command = new CreateOrderCommand("c-1", "EUR",
                List.of(new CreateOrderCommand.Line("p-1", 1, BigDecimal.ONE)));

        assertThat(service.create(command).id()).isNotEqualTo(service.create(command).id());
    }

    @Test
    void doesNotSaveInvalidOrder() {
        assertThatThrownBy(() -> service.create(new CreateOrderCommand("c-1", "EUR", List.of())))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> service.create(new CreateOrderCommand("c-1", "XYZ",
                List.of(new CreateOrderCommand.Line("p-1", 1, BigDecimal.ONE)))))
                .isInstanceOf(InvalidMoneyException.class);

        assertThat(repository.saved).isEmpty();
        assertThat(metrics.recorded).isEmpty();
    }

    @Test
    void treatsMissingLinesAsEmptyOrder() {
        assertThatThrownBy(() -> service.create(new CreateOrderCommand("c-1", "EUR", null)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessage("Order must have at least one line");

        assertThat(repository.saved).isEmpty();
    }
}
