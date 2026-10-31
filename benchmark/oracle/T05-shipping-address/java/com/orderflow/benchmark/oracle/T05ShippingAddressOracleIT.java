package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T05: required shipping address on POST /api/v1/orders, returned by POST and GET. */
@OracleSpringBootTest
class T05ShippingAddressOracleIT {

    private static final String ADDRESS =
            "{\"street\":\"Main St 1\",\"city\":\"Berlin\",\"postalCode\":\"10115\",\"country\":\"DE\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void addressIsStoredAndReturned() throws Exception {
        MvcResult created = postOrder(order(ADDRESS));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        JsonNode expected = jsonMapper.readTree(ADDRESS);

        assertThat(body.path("shippingAddress")).isEqualTo(expected);
        JsonNode read = json(mockMvc.perform(get("/api/v1/orders/" + body.path("id").asString())).andReturn());
        assertThat(read.path("shippingAddress")).isEqualTo(expected);
    }

    @Test
    void addressIsRequired() throws Exception {
        MvcResult result = postOrder("{\"customerId\":\"c-1\",\"currency\":\"EUR\","
                + "\"lines\":[{\"productId\":\"p-1\",\"quantity\":1,\"unitPrice\":5.00}]}");

        assertValidationError(result, "shippingAddress");
    }

    @Test
    void invalidFieldsAreRejected() throws Exception {
        // (invalid field, address)
        List<Map.Entry<String, String>> invalid = List.of(
                Map.entry("country", address("Main St 1", "Berlin", "10115", "de")),
                Map.entry("country", address("Main St 1", "Berlin", "10115", "DEU")),
                Map.entry("street", address("", "Berlin", "10115", "DE")),
                Map.entry("street", address("x".repeat(201), "Berlin", "10115", "DE")),
                Map.entry("city", address("Main St 1", "y".repeat(101), "10115", "DE")),
                Map.entry("postalCode", address("Main St 1", "Berlin", "1".repeat(21), "DE")));
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> entry : invalid) {
            MvcResult result = postOrder(order(entry.getValue()));
            String field = "shippingAddress." + entry.getKey();
            if (result.getResponse().getStatus() != 400
                    || !result.getResponse().getContentAsString().contains(field)) {
                failures.add(field + " -> " + result.getResponse().getStatus() + " "
                        + result.getResponse().getContentAsString());
            }
        }

        assertThat(failures).as("invalid addresses accepted or reported without the field").isEmpty();
    }

    private void assertValidationError(MvcResult result, String field) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        List<String> fields = new ArrayList<>();
        json(result).path("errors").forEach(error -> fields.add(error.path("field").asString()));
        assertThat(fields).anyMatch(name -> name.startsWith(field));
    }

    private static String address(String street, String city, String postalCode, String country) {
        return "{\"street\":\"" + street + "\",\"city\":\"" + city + "\",\"postalCode\":\"" + postalCode
                + "\",\"country\":\"" + country + "\"}";
    }

    private static String order(String address) {
        return "{\"customerId\":\"c-1\",\"currency\":\"EUR\",\"shippingAddress\":" + address
                + ",\"lines\":[{\"productId\":\"p-1\",\"quantity\":1,\"unitPrice\":5.00}]}";
    }

    private MvcResult postOrder(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
