package com.orderflow.benchmark.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** T10: an unavailable database gives a safe 500 problem, and the failure is logged with the correlation ID. */
@OracleSpringBootTest(properties = {"orderflow.outbox.relay.enabled=false",
        "spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.validation-timeout=1000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=3"})
@ExtendWith(OutputCaptureExtension.class)
class T10DatabaseOutageOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void databaseUnavailable(CapturedOutput output) throws Exception {
        String correlationId = "t10-db-" + UUID.randomUUID().toString().substring(0, 8);
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        MvcResult result;
        try {
            result = mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                    .header("X-Correlation-Id", correlationId)).andReturn();
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String body = result.getResponse().getContentAsString();
        JsonNode problem = jsonMapper.readTree(body);
        assertThat(problem.path("title").asString()).isEqualTo("Internal error");
        assertThat(problem.path("detail").asString()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.path("correlationId").asString()).isEqualTo(correlationId);
        assertThat(body).doesNotContain(T10ErrorResponsesOracleIT.INTERNALS);
        assertThat(output.getAll().lines()
                .filter(line -> line.contains("ERROR") && line.contains(correlationId)))
                .as("ERROR log line with the correlation ID").isNotEmpty();
    }
}
