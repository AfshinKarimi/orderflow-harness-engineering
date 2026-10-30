package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06: GET /api/v1/orders/{id} includes the status history. PAYMENT_PENDING is set by SQL (not a change made by
 * the application, so not recorded); payment results arrive through Kafka.
 */
@OracleSpringBootTest
class T06StatusHistoryOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafka;

    private OrdersOracle orders;
    private PaymentEventsOracle payments;

    @BeforeEach
    void setUp() {
        orders = new OrdersOracle(mockMvc, jsonMapper, jdbcTemplate);
        payments = new PaymentEventsOracle(kafkaTemplate, jdbcTemplate, kafka.getBootstrapServers());
    }

    @Test
    void newOrderHasOneCreatedEntry() throws Exception {
        JsonNode order = orders.fetch(orders.create());

        assertThat(statuses(order)).containsExactly("CREATED");
        Instant changedAt = Instant.parse(order.path("statusHistory").get(0).path("changedAt").asString());
        Instant createdAt = Instant.parse(order.path("createdAt").asString());
        assertThat(Duration.between(createdAt, changedAt).abs()).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void paymentResultAddsAnEntry() throws Exception {
        UUID order = orders.create();
        UUID attempt = orders.setPaymentPending(order);

        payments.awaitConsumed(payments.succeeded(order, attempt));

        JsonNode read = orders.fetch(order);
        assertThat(statuses(read)).containsExactly("CREATED", "PAID");
        assertChronological(read);
    }

    @Test
    void duplicateEventAddsNoEntry() throws Exception {
        UUID order = orders.create();
        UUID attempt = orders.setPaymentPending(order);
        UUID eventId = UUID.randomUUID();
        String payload = "{\"orderId\":\"" + order + "\",\"attemptId\":\"" + attempt + "\"}";

        payments.publish(eventId, "PaymentSucceeded", order, payload);
        payments.publish(eventId, "PaymentSucceeded", order, payload);
        // Same key, same partition: once this later event is consumed, the duplicate before it was handled too.
        payments.awaitConsumed(payments.succeeded(order, UUID.randomUUID()));

        assertThat(statuses(orders.fetch(order))).containsExactly("CREATED", "PAID");
    }

    @Test
    void staleResultAddsNoEntry() throws Exception {
        UUID order = orders.create();
        orders.setPaymentPending(order);

        payments.awaitConsumed(payments.failed(order, UUID.randomUUID()));

        assertThat(statuses(orders.fetch(order))).containsExactly("CREATED");
    }

    static List<String> statuses(JsonNode order) {
        assertThat(order.path("statusHistory").isArray()).as("statusHistory is an array").isTrue();
        List<String> statuses = new ArrayList<>();
        order.path("statusHistory").forEach(entry -> statuses.add(entry.path("status").asString()));
        return statuses;
    }

    private static void assertChronological(JsonNode order) {
        List<Instant> times = new ArrayList<>();
        order.path("statusHistory").forEach(entry -> times.add(Instant.parse(entry.path("changedAt").asString())));
        assertThat(times).isSorted();
    }
}
