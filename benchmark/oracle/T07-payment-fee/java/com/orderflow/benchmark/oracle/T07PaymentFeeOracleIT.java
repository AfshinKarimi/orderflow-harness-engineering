package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T07: GET /api/v1/payments/fees?orderId=: 1.5 % of the order total, half-up to 2 decimals, at least 0.50. */
@OracleSpringBootTest
class T07PaymentFeeOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void feeIsOnePointFivePercentOfTheTotal() throws Exception {
        assertFee(order("EUR", "120.00"), "1.80");
    }

    @Test
    void feeIsRoundedHalfUp() throws Exception {
        assertFee(order("EUR", "103.00"), "1.55"); // 1.545: half-even would give 1.54
    }

    @Test
    void feeHasTwoDecimals() throws Exception {
        assertFee(order("EUR", "1234.57"), "18.52"); // 18.51855
    }

    @Test
    void feeIsAtLeastFiftyCents() throws Exception {
        assertFee(order("EUR", "10.00"), "0.50"); // 0.15
    }

    @Test
    void responseNamesTheOrderTotalAndCurrency() throws Exception {
        UUID order = order("USD", "120.00");

        JsonNode quote = quote(order);

        assertThat(quote.path("orderId").asString()).isEqualTo(order.toString());
        assertThat(quote.path("currency").asString()).isEqualTo("USD");
        assertThat(quote.path("orderTotal").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void rejectsInvalidAndUnknownOrders() throws Exception {
        for (String query : new String[] {"", "?orderId=abc"}) {
            MvcResult invalid = mockMvc.perform(get("/api/v1/payments/fees" + query)).andReturn();
            assertThat(invalid.getResponse().getStatus()).as("GET %s", query).isEqualTo(400);
            assertThat(invalid.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        }
        MvcResult unknown = mockMvc.perform(get("/api/v1/payments/fees?orderId=" + UUID.randomUUID())).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private void assertFee(UUID order, String expectedFee) throws Exception {
        assertThat(quote(order).path("fee").decimalValue()).isEqualByComparingTo(new BigDecimal(expectedFee));
    }

    private JsonNode quote(UUID order) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/payments/fees?orderId=" + order)).andReturn();
        assertThat(result.getResponse().getStatus()).as("fee quote").isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID order(String currency, String total) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"t07\",\"currency\":\"" + currency + "\","
                        + "\"lines\":[{\"productId\":\"p-1\",\"quantity\":1,\"unitPrice\":" + total + "}]}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).path("id").asString());
    }
}
