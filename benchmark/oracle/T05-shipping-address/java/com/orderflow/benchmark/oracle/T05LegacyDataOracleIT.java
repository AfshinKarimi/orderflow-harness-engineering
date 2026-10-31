package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T05 on a database that already contains an order: the migration must succeed (the context starts) and the
 * order is returned with "shippingAddress": null.
 */
@LegacyDataSpringBootTest
class T05LegacyDataOracleIT {

    private static final UUID ORDER = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void legacyDatabase(DynamicPropertyRegistry registry) {
        LegacyDatabase.register(registry, "t05",
                "INSERT INTO orders.orders (id, customer_id, status, currency, created_at, version)"
                        + " VALUES ('" + ORDER + "', 'c-legacy', 'CREATED', 'EUR', now(), 0)",
                "INSERT INTO orders.order_lines (order_id, line_no, product_id, quantity, unit_price)"
                        + " VALUES ('" + ORDER + "', 0, 'p-1', 1, 5.00)");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void existingOrderHasNoAddress() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + ORDER)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("shippingAddress")).as("shippingAddress present").isTrue();
        assertThat(body.path("shippingAddress").isNull()).as("shippingAddress is null").isTrue();
    }
}
