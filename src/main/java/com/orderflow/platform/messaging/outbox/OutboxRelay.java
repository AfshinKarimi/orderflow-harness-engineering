package com.orderflow.platform.messaging.outbox;

import com.orderflow.platform.observability.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes pending outbox events to Kafka, oldest first, at least once (ADR-0003).
 * An event is marked published only after the broker acknowledged it. The first failure stops the batch
 * so that later events for the same key cannot overtake an earlier one; the next run retries.
 */
@Component
class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final MeterRegistry meterRegistry;

    OutboxRelay(JdbcTemplate jdbcTemplate, KafkaTemplate<String, String> kafkaTemplate, Clock clock,
                MeterRegistry meterRegistry,
                @Value("${orderflow.outbox.relay.batch-size:100}") int batchSize,
                @Value("${orderflow.outbox.relay.send-timeout:15s}") Duration sendTimeout) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.meterRegistry = meterRegistry;
    }

    /** @return number of events published in this run */
    @Transactional
    public int publishPending() {
        List<PendingEvent> batch = jdbcTemplate.query("""
                        SELECT id, topic, msg_key, event_type, payload::text AS payload, occurred_at, correlation_id
                        FROM messaging.outbox_events
                        WHERE published_at IS NULL
                        ORDER BY seq
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""",
                (rs, rowNum) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("msg_key"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("correlation_id")),
                batchSize);

        int published = 0;
        for (PendingEvent event : batch) {
            try {
                send(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(event, e);
                break;
            }
            Instant publishedAt = clock.instant();
            jdbcTemplate.update(
                    "UPDATE messaging.outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                    Timestamp.from(publishedAt), event.id());
            recordDelay(event, publishedAt);
            published++;
        }
        return published;
    }

    private void send(PendingEvent event) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.payload());
        record.headers().add("eventId", event.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.type().getBytes(StandardCharsets.UTF_8));
        if (event.correlationId() != null) {
            record.headers().add(CorrelationId.MDC_KEY, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void recordFailure(PendingEvent event, Exception e) {
        Counter.builder("orderflow.outbox.publish.failures")
                .description("Failed attempts to publish an outbox event to Kafka")
                .tag("topic", event.topic())
                .register(meterRegistry)
                .increment();
        String error = (e.getClass().getSimpleName() + ": " + e.getMessage());
        if (event.correlationId() != null) {
            MDC.put(CorrelationId.MDC_KEY, event.correlationId());
        }
        try {
            LOG.warn("Outbox event {} ({}) not published, will retry: {}", event.id(), event.type(), error);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
        jdbcTemplate.update(
                "UPDATE messaging.outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error, event.id());
    }

    /** Time from the business commit until Kafka acknowledged the event: the eventual-consistency delay we own. */
    private void recordDelay(PendingEvent event, Instant publishedAt) {
        Timer.builder("orderflow.outbox.publish.delay")
                .description("Time from the event being stored in the outbox until Kafka acknowledged it")
                .tag("topic", event.topic())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.between(event.occurredAt(), publishedAt));
    }

    private record PendingEvent(UUID id, String topic, String key, String type, String payload, Instant occurredAt,
                                String correlationId) {
    }
}
