package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T10: every error of /api/v1/orders is problem+json with a correlationId equal to the X-Correlation-Id header. */
@OracleSpringBootTest(properties = {"orderflow.outbox.relay.enabled=false",
        "spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.validation-timeout=1000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=3"})
class T10ErrorResponsesOracleIT {

    static final String[] INTERNALS = {"Exception", "java.", "tools.jackson", "org.", "SQL", "jdbc",
        "Cannot deserialize", "line:", "\tat "};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void unknownOrder() throws Exception {
        UUID id = UUID.randomUUID();

        JsonNode problem = problem(mockMvc.perform(get("/api/v1/orders/" + id)).andReturn(), 404);

        assertThat(problem.path("title").asString()).isEqualTo("Order not found");
        assertThat(problem.path("orderId").asString()).isEqualTo(id.toString());
    }

    @Test
    void malformedJsonAndWrongTypes() throws Exception {
        for (String body : new String[] {"{\"customerId\":",
            "{\"customerId\":\"c-1\",\"currency\":\"EUR\",\"lines\":[{\"productId\":\"p-1\",\"quantity\":\"two\","
                    + "\"unitPrice\":1.00}]}"}) {
            MvcResult result = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn();

            JsonNode problem = problem(result, 400);
            assertThat(problem.path("title").asString()).isEqualTo("Malformed request");
            assertThat(problem.path("detail").asString()).doesNotContain(INTERNALS);
        }
    }

    @Test
    void unsupportedMediaTypeAndMethod() throws Exception {
        problem(mockMvc.perform(post("/api/v1/orders").contentType(MediaType.TEXT_PLAIN).content("x")).andReturn(),
                415);
        problem(mockMvc.perform(delete("/api/v1/orders/" + UUID.randomUUID())).andReturn(), 405);
    }

    @Test
    void existingErrorsKeepTheirShapeAndGetACorrelationId() throws Exception {
        JsonNode validation = problem(mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"EUR\",\"lines\":[]}")).andReturn(), 400);
        assertThat(validation.path("title").asString()).isEqualTo("Invalid request");
        assertThat(validation.path("errors").isArray()).isTrue();

        JsonNode domain = problem(mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"c-1\",\"currency\":\"EUR\",\"lines\":[{\"productId\":\"p-1\","
                        + "\"quantity\":1,\"unitPrice\":10.005}]}")).andReturn(), 400);
        assertThat(domain.path("title").asString()).isEqualTo("Business rule violated");
        assertThat(domain.path("detail").asString()).isEqualTo("Amount 10.005 has more than 2 decimal places for EUR");
    }

    @Test
    void callerCorrelationIdIsUsed() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                .header("X-Correlation-Id", "t10-abc")).andReturn();

        assertThat(problem(result, 404).path("correlationId").asString()).isEqualTo("t10-abc");
    }

    /**
     * Asserts status, problem+json, the RFC 9457 members and correlationId = X-Correlation-Id header. A missing
     * "type" means "about:blank" (RFC 9457 §3.1.1); Spring omits it in that case.
     */
    JsonNode problem(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        for (String member : new String[] {"title", "status", "detail", "correlationId"}) {
            assertThat(problem.has(member)).as("problem has %s: %s", member, problem).isTrue();
        }
        if (problem.has("type")) {
            assertThat(problem.path("type").isString()).as("type is a URI string").isTrue();
        }
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        String header = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(header).isNotBlank();
        assertThat(problem.path("correlationId").asString()).isEqualTo(header);
        return problem;
    }
}
