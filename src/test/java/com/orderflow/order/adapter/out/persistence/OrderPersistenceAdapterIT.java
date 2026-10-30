package com.orderflow.order.adapter.out.persistence;

import com.orderflow.TestcontainersConfiguration;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, OrderPersistenceAdapter.class})
class OrderPersistenceAdapterIT {

    @Autowired
    private OrderPersistenceAdapter adapter;

    @Autowired
    private SpringDataOrderRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savedOrderRoundTripsAllFieldsAndLineOrder() {
        Instant createdAt = Instant.parse("2026-10-30T12:00:00Z");
        Order order = Order.create(OrderId.newId(), "c-1", List.of(
                new OrderLine("p-2", 2, Money.of(new BigDecimal("19.99"), "EUR")),
                new OrderLine("p-1", 1, Money.of(new BigDecimal("5"), "EUR"))), createdAt);

        adapter.save(order);
        entityManager.flush();
        entityManager.clear();

        OrderJpaEntity loaded = repository.findById(order.id().value()).orElseThrow();
        assertThat(loaded.getCustomerId()).isEqualTo("c-1");
        assertThat(loaded.getStatus()).isEqualTo("CREATED");
        assertThat(loaded.getCurrency()).isEqualTo("EUR");
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.getLines()).extracting(OrderLineJpaEmbeddable::getProductId).containsExactly("p-2", "p-1");
        assertThat(loaded.getLines().getFirst().getQuantity()).isEqualTo(2);
        assertThat(loaded.getLines().getFirst().getUnitPrice()).isEqualByComparingTo("19.99");
    }
}
