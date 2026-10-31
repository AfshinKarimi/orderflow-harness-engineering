package com.orderflow.benchmark.oracle;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Order setup and inspection through the existing REST contract, plus SQL for states without an endpoint. */
final class OrdersOracle {

    static final String ORDER_JSON = "{\"customerId\":\"%s\",\"currency\":\"EUR\","
            + "\"lines\":[{\"productId\":\"p-1\",\"quantity\":2,\"unitPrice\":19.99}]}";

    private final MockMvc mockMvc;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbcTemplate;

    OrdersOracle(MockMvc mockMvc, JsonMapper jsonMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jsonMapper = jsonMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    UUID create() throws Exception {
        return create(ORDER_JSON.formatted("oracle-" + UUID.randomUUID()));
    }

    UUID create(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
        assertThat(result.getResponse().getStatus()).as("create order").isEqualTo(201);
        return UUID.fromString(json(result).path("id").asString());
    }

    JsonNode fetch(UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + orderId)).andReturn();
        assertThat(result.getResponse().getStatus()).as("get order").isEqualTo(200);
        return json(result);
    }

    String status(UUID orderId) throws Exception {
        return fetch(orderId).path("status").asString();
    }

    /** No endpoint starts a payment in the base application, so the state is set directly. */
    UUID setPaymentPending(UUID orderId) {
        UUID attempt = UUID.randomUUID();
        int updated = jdbcTemplate.update(
                "UPDATE orders.orders SET status = 'PAYMENT_PENDING', payment_attempt_id = ? WHERE id = ?",
                attempt, orderId);
        assertThat(updated).isEqualTo(1);
        return attempt;
    }

    JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
