package com.orderflow.benchmark.oracle;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.CreateOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T08 guards: an order that is not stored produces no event. These pass on the base commit (it publishes nothing)
 * and must keep passing.
 */
@OracleSpringBootTest(properties = {"orderflow.outbox.relay.enabled=true", "orderflow.outbox.relay.interval=200ms"})
class T08OrderPlacedGuardIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreateOrderUseCase createOrder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void rejectedOrderPublishesNothing() throws Exception {
        String customer = "t08-rejected-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"currency\":\"EUR\",\"lines\":[]}"))
                .andExpect(status().isBadRequest());

        assertNothingPublishedFor(customer);
    }

    @Test
    void rolledBackOrderPublishesNothing() {
        String customer = "t08-rolled-back-" + UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            createOrder.create(new CreateOrderCommand(customer, "EUR",
                    List.of(new CreateOrderCommand.Line("p-1", 1, BigDecimal.ONE))));
            status.setRollbackOnly();
        });

        assertNothingPublishedFor(customer);
    }

    private void assertNothingPublishedFor(String customer) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messaging.outbox_events WHERE payload::text LIKE ?", Integer.class,
                "%" + customer + "%")).as("outbox rows").isZero();
        assertThat(OrderEventsOracle.find(kafka.getBootstrapServers(),
                record -> record.value() != null && record.value().contains(customer), Duration.ofSeconds(3)))
                .as("events on %s", OrderEventsOracle.TOPIC).isEmpty();
    }
}
