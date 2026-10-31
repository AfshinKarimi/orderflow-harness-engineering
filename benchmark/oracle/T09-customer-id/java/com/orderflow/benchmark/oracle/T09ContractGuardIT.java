package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T09 guards: the REST contract and the database schema stay exactly as on the base commit. */
@OracleSpringBootTest
class T09ContractGuardIT {

    private static final String LINES = "\"lines\":[{\"productId\":\"p-1\",\"quantity\":2,\"unitPrice\":19.99}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createAndGetResponsesAreUnchanged() throws Exception {
        MvcResult created = postOrder("{\"customerId\":\"c-9\",\"currency\":\"EUR\"," + LINES + "}");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        JsonNode read = json(mockMvc.perform(get("/api/v1/orders/" + body.path("id").asString())).andReturn());

        JsonNode expected = jsonMapper.readTree("""
                {"customerId":"c-9","status":"CREATED","currency":"EUR","totalAmount":39.98,
                 "lines":[{"productId":"p-1","quantity":2,"unitPrice":19.99,"lineTotal":39.98}]}""");
        for (JsonNode response : new JsonNode[] {body, read}) {
            assertThat(response.has("id") && response.has("createdAt")).isTrue();
            ObjectNode stable = ((ObjectNode) response.deepCopy());
            stable.remove("id");
            stable.remove("createdAt");
            assertThat(stable).isEqualTo(expected);
        }
    }

    @Test
    void validationErrorsAreUnchanged() throws Exception {
        assertCustomerIdError(postOrder("{\"currency\":\"EUR\"," + LINES + "}"), "must not be blank");
        assertCustomerIdError(postOrder("{\"customerId\":\"" + "c".repeat(101) + "\",\"currency\":\"EUR\"," + LINES + "}"),
                "size must be between 0 and 100");
    }

    @Test
    void noMigrationWasAdded() {
        assertThat(jdbcTemplate.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL",
                String.class)).containsExactlyInAnyOrder("1", "2", "3", "4", "5");
    }

    private void assertCustomerIdError(MvcResult result, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = json(result);
        assertThat(problem.path("title").asString()).isEqualTo("Invalid request");
        assertThat(problem.path("detail").asString()).isEqualTo("Request validation failed");
        assertThat(problem.path("errors")).isEqualTo(jsonMapper.readTree(
                "[{\"field\":\"customerId\",\"message\":\"" + message + "\"}]"));
    }

    private MvcResult postOrder(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }
}
