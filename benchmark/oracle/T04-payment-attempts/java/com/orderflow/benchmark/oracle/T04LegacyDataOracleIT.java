package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T04 on a database that already has an order with a payment attempt: it counts as the first attempt. */
@LegacyDataSpringBootTest
class T04LegacyDataOracleIT {

    private static final UUID ORDER = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT = UUID.fromString("40000000-0000-0000-0000-0000000000a1");

    @DynamicPropertySource
    static void legacyDatabase(DynamicPropertyRegistry registry) {
        LegacyDatabase.register(registry, "t04",
                "INSERT INTO orders.orders (id, customer_id, status, currency, created_at, version, payment_attempt_id)"
                        + " VALUES ('" + ORDER + "', 'c-legacy', 'PAYMENT_PENDING', 'EUR', now(), 0, '" + ATTEMPT + "')",
                "INSERT INTO orders.order_lines (order_id, line_no, product_id, quantity, unit_price)"
                        + " VALUES ('" + ORDER + "', 0, 'p-1', 1, 5.00)");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void existingPendingOrderCountsAsFirstAttempt() throws Exception {
        PaymentEventsOracle payments =
                new PaymentEventsOracle(kafkaTemplate, jdbcTemplate, kafka.getBootstrapServers());
        payments.awaitConsumed(payments.failed(ORDER, ATTEMPT));

        for (int expectedNumber = 2; expectedNumber <= 3; expectedNumber++) {
            MvcResult started = request();
            assertThat(started.getResponse().getStatus()).isEqualTo(202);
            JsonNode body = jsonMapper.readTree(started.getResponse().getContentAsString());
            assertThat(body.path("attemptNumber").asInt()).isEqualTo(expectedNumber);
            UUID attempt = UUID.fromString(body.path("attemptId").asString());
            payments.awaitConsumed(payments.failed(ORDER, attempt));
        }

        assertThat(request().getResponse().getStatus()).as("fourth attempt").isEqualTo(409);
    }

    private MvcResult request() throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + ORDER + "/payment-requests")).andReturn();
    }
}
