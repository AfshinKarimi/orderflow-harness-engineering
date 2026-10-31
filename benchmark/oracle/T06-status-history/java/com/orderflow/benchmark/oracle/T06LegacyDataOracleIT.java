package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** T06 on a database that already contains an order: it gets one CREATED entry at its creation time. */
@LegacyDataSpringBootTest
class T06LegacyDataOracleIT {

    private static final UUID ORDER = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");

    @DynamicPropertySource
    static void legacyDatabase(DynamicPropertyRegistry registry) {
        LegacyDatabase.register(registry, "t06",
                "INSERT INTO orders.orders (id, customer_id, status, currency, created_at, version)"
                        + " VALUES ('" + ORDER + "', 'c-legacy', 'CREATED', 'EUR', '" + CREATED_AT + "', 0)",
                "INSERT INTO orders.order_lines (order_id, line_no, product_id, quantity, unit_price)"
                        + " VALUES ('" + ORDER + "', 0, 'p-1', 1, 5.00)");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void existingOrderIsBackfilledWithItsCreation() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + ORDER)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode order = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(T06StatusHistoryOracleIT.statuses(order)).containsExactly("CREATED");
        assertThat(Instant.parse(order.path("statusHistory").get(0).path("changedAt").asString()))
                .isEqualTo(CREATED_AT);
    }
}
