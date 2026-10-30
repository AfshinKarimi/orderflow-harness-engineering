package com.orderflow.platform.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    /** Backlog of unpublished events: grows while Kafka is unavailable or an event cannot be sent. */
    @Bean
    MeterBinder outboxPendingGauge(JdbcTemplate jdbcTemplate) {
        return registry -> Gauge.builder("orderflow.outbox.pending", () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM messaging.outbox_events WHERE published_at IS NULL", Long.class))
                .description("Outbox events not yet published to Kafka")
                .register(registry);
    }

    /** Runs the relay on a fixed delay. Disabled in tests, which call the relay explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "orderflow.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelaySchedule {

        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${orderflow.outbox.relay.interval:1s}")
        void run() {
            relay.publishPending();
        }
    }
}
