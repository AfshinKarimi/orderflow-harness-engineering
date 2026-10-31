package com.orderflow.benchmark.oracle;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers for the hidden benchmark oracle. Independent of the project's own test helpers, which the agent may
 * change.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OracleContainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer oraclePostgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer oracleKafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    }
}
