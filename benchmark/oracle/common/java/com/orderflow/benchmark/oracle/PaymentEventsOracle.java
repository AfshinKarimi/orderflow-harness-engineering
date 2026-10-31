package com.orderflow.benchmark.oracle;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes payment results with the existing event contract (topic, headers, JSON) and waits until the order
 * module has processed or dead-lettered them.
 */
final class PaymentEventsOracle {

    static final String TOPIC = "orderflow.payment.events";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final String bootstrapServers;

    PaymentEventsOracle(KafkaTemplate<String, String> kafkaTemplate, JdbcTemplate jdbcTemplate,
                        String bootstrapServers) {
        this.kafkaTemplate = kafkaTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapServers = bootstrapServers;
    }

    UUID succeeded(UUID orderId, UUID attemptId) {
        return publish(UUID.randomUUID(), "PaymentSucceeded", orderId,
                "{\"orderId\":\"" + orderId + "\",\"attemptId\":\"" + attemptId + "\"}");
    }

    UUID failed(UUID orderId, UUID attemptId) {
        return publish(UUID.randomUUID(), "PaymentFailed", orderId,
                "{\"orderId\":\"" + orderId + "\",\"attemptId\":\"" + attemptId + "\",\"reason\":\"declined\"}");
    }

    UUID publish(UUID eventId, String type, UUID orderId, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, orderId.toString(), payload);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        return eventId;
    }

    /** Waits until the event was processed (dedup row of consumer "order") or moved to the dead-letter topic. */
    void awaitConsumed(UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "oracle-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> deadLetters = new KafkaConsumer<>(config)) {
            deadLetters.subscribe(List.of(TOPIC + ".DLT"));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (processed(eventId)) {
                    return;
                }
                for (var record : deadLetters.poll(Duration.ofMillis(250))) {
                    Header header = record.headers().lastHeader("eventId");
                    if (header != null && eventId.toString().equals(new String(header.value(),
                            StandardCharsets.UTF_8))) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("event " + eventId + " was neither processed nor dead-lettered within " + TIMEOUT);
    }

    private boolean processed(UUID eventId) {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messaging.processed_events WHERE event_id = ? AND consumer = 'order'",
                Integer.class, eventId);
        return rows != null && rows > 0;
    }
}
