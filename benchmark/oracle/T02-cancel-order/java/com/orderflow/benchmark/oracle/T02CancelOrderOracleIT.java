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
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T02: POST /api/v1/orders/{id}/cancellation. */
@OracleSpringBootTest
class T02CancelOrderOracleIT {

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
    void cancelsACreatedOrder() throws Exception {
        UUID order = orders.create();

        MvcResult result = cancel(order);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(orders.json(result).path("id").asString()).isEqualTo(order.toString());
        assertThat(orders.json(result).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(orders.status(order)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelsAnOrderWhosePaymentFailed() throws Exception {
        UUID order = paymentFailedOrder();

        MvcResult result = cancel(order);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(orders.status(order)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingTwiceIsHarmless() throws Exception {
        UUID order = orders.create();

        MvcResult first = cancel(order);
        MvcResult second = cancel(order);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(orders.json(second)).isEqualTo(orders.json(first));
    }

    @Test
    void rejectsPendingAndPaidOrders() throws Exception {
        UUID pending = orders.create();
        orders.setPaymentPending(pending);
        UUID paid = orders.create();
        UUID attempt = orders.setPaymentPending(paid);
        payments.awaitConsumed(payments.succeeded(paid, attempt));
        assertThat(orders.status(paid)).isEqualTo("PAID");

        for (UUID order : new UUID[] {pending, paid}) {
            MvcResult result = cancel(order);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        assertThat(orders.status(pending)).isEqualTo("PAYMENT_PENDING");
        assertThat(orders.status(paid)).isEqualTo("PAID");
    }

    @Test
    void rejectsUnknownAndInvalidIds() throws Exception {
        MvcResult unknown = cancel(UUID.randomUUID());
        MvcResult invalid = mockMvc.perform(post("/api/v1/orders/not-a-uuid/cancellation")).andReturn();

        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(invalid.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    void latePaymentResultDoesNotChangeACancelledOrder() throws Exception {
        UUID order = orders.create();
        UUID attempt = orders.setPaymentPending(order);
        payments.awaitConsumed(payments.failed(order, attempt));
        assertThat(cancel(order).getResponse().getStatus()).isEqualTo(200);

        payments.awaitConsumed(payments.succeeded(order, attempt));

        assertThat(orders.status(order)).isEqualTo("CANCELLED");
    }

    private UUID paymentFailedOrder() throws Exception {
        UUID order = orders.create();
        UUID attempt = orders.setPaymentPending(order);
        payments.awaitConsumed(payments.failed(order, attempt));
        await().until(() -> "PAYMENT_FAILED".equals(orders.status(order)));
        return order;
    }

    private MvcResult cancel(UUID order) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + order + "/cancellation")).andReturn();
    }
}
