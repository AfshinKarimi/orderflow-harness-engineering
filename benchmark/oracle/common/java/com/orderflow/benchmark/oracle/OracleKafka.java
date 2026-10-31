package com.orderflow.benchmark.oracle;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka only, for contexts whose database comes from {@link LegacyDatabase}. */
@TestConfiguration(proxyBeanMethods = false)
public class OracleKafka {

    @Bean
    @ServiceConnection
    KafkaContainer oracleLegacyKafka() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.2"));
    }
}
