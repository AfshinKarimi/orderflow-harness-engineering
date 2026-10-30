package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T04: POST /api/v1/orders/{id}/payment-requests, at most 3 attempts per order. */
@OracleSpringBootTest
class T04PaymentAttemptsOracleIT {

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
    void firstRequestStartsAttemptOne() throws Exception {
        UUID order = orders.create();

        JsonNode attempt = accepted(order);

        assertThat(attempt.path("orderId").asString()).isEqualTo(order.toString());
        assertThat(UUID.fromString(attempt.path("attemptId").asString())).isNotNull();
        assertThat(attempt.path("attemptNumber").asInt()).isEqualTo(1);
        assertThat(orders.status(order)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void repeatedRequestReturnsTheCurrentAttempt() throws Exception {
        UUID order = orders.create();

        JsonNode first = accepted(order);
        JsonNode second = accepted(order);

        assertThat(second.path("attemptId")).isEqualTo(first.path("attemptId"));
        assertThat(second.path("attemptNumber").asInt()).isEqualTo(1);
    }

    @Test
    void failedAttemptCanBeRetried() throws Exception {
        UUID order = orders.create();
        JsonNode first = accepted(order);
        fail(order, first);

        JsonNode second = accepted(order);

        assertThat(second.path("attemptNumber").asInt()).isEqualTo(2);
        assertThat(second.path("attemptId")).isNotEqualTo(first.path("attemptId"));
        assertThat(orders.status(order)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void fourthAttemptIsRejected() throws Exception {
        UUID order = orders.create();
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode started = accepted(order);
            assertThat(started.path("attemptNumber").asInt()).isEqualTo(attempt);
            fail(order, started);
        }

        assertConflict(request(order));
        assertThat(orders.status(order)).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void paidOrderIsRejected() throws Exception {
        UUID order = orders.create();
        JsonNode attempt = accepted(order);
        payments.awaitConsumed(payments.succeeded(order, UUID.fromString(attempt.path("attemptId").asString())));
        assertThat(orders.status(order)).isEqualTo("PAID");

        assertConflict(request(order));
    }

    @Test
    void rejectsUnknownAndInvalidIds() throws Exception {
        MvcResult unknown = request(UUID.randomUUID());
        MvcResult invalid = mockMvc.perform(post("/api/v1/orders/not-a-uuid/payment-requests")).andReturn();

        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(invalid.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private JsonNode accepted(UUID order) throws Exception {
        MvcResult result = request(order);
        assertThat(result.getResponse().getStatus()).as("payment request for %s", order).isEqualTo(202);
        return orders.json(result);
    }

    private void fail(UUID order, JsonNode attempt) throws Exception {
        payments.awaitConsumed(payments.failed(order, UUID.fromString(attempt.path("attemptId").asString())));
        assertThat(orders.status(order)).isEqualTo("PAYMENT_FAILED");
    }

    private MvcResult request(UUID order) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + order + "/payment-requests")).andReturn();
    }

    private static void assertConflict(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }
}
