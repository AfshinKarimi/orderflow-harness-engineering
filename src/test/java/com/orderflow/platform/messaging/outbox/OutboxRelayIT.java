package com.orderflow.platform.messaging.outbox;

import com.orderflow.KafkaTestcontainersConfiguration;
import com.orderflow.TestcontainersConfiguration;
import com.orderflow.platform.messaging.api.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * ADR-0003 behaviour of the transactional outbox against real PostgreSQL and Kafka.
 * The scheduled relay is disabled in tests; each test triggers it explicitly.
 */
// Same annotations as the other @SpringBootTest ITs so the cached context (and its containers) is reused.
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class OutboxRelayIT {

    record SampleEvent(String orderId, String status) {
    }

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry meterRegistry;

    private String topic;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM messaging.outbox_events");
        topic = "test-" + UUID.randomUUID();
    }

    @Test
    void appendRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> outboxWriter.append(topic, "o-1", new SampleEvent("o-1", "PAID")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(pendingCount()).isZero();
    }

    @Test
    void eventIsDiscardedWhenTheBusinessTransactionRollsBack() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            outboxWriter.append(topic, "o-1", new SampleEvent("o-1", "PAID"));
            throw new IllegalStateException("business update failed");
        })).hasMessage("business update failed");

        assertThat(pendingCount()).isZero();
    }

    /** Scenario: the application crashes after committing, before the relay published anything. */
    @Test
    void committedEventIsPublishedOnceTheRelayRunsAgain() {
        UUID eventId = appendInTransaction("o-1", new SampleEvent("o-1", "PAID"));

        // "Crash": the event is committed but nothing has been sent.
        assertThat(consume(1, Duration.ofSeconds(2))).isEmpty();
        assertThat(pendingCount()).isEqualTo(1);

        // "Restart": the relay picks up what was left behind.
        assertThat(relay.publishPending()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = consume(1, Duration.ofSeconds(10));
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.key()).isEqualTo("o-1");
        assertThat(header(record, "eventId")).isEqualTo(eventId.toString());
        assertThat(header(record, "eventType")).isEqualTo("SampleEvent");
        assertThat(record.value()).contains("\"orderId\"").contains("\"PAID\"");
        assertThat(pendingCount()).isZero();
        assertThat(relay.publishPending()).as("already published events are not sent again").isZero();

        // The publish delay includes the time the event sat in the outbox during the "crash" (>= 2 s above).
        var delay = meterRegistry.get("orderflow.outbox.publish.delay").tag("topic", topic).timer();
        assertThat(delay.count()).isEqualTo(1);
        assertThat(delay.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(2000);
    }

    /** Scenario: Kafka is temporarily unavailable. */
    @Test
    void kafkaOutageKeepsEventPendingAndPublishesAfterRecovery() {
        UUID eventId = appendInTransaction("o-2", new SampleEvent("o-2", "PAID"));

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThat(relay.publishPending()).isZero();

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT published_at, attempts, last_error FROM messaging.outbox_events WHERE id = ?", eventId);
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).isNotBlank();
            assertThat(meterRegistry.get("orderflow.outbox.pending").gauge().value()).isEqualTo(1.0);
            assertThat(meterRegistry.get("orderflow.outbox.publish.failures").tag("topic", topic).counter().count())
                    .isEqualTo(1.0);
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        // Broker is back: the next relay runs publish the event that was kept.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> relay.publishPending() == 1);

        List<ConsumerRecord<String, String>> records = consume(1, Duration.ofSeconds(10));
        assertThat(records).extracting(r -> header(r, "eventId")).containsExactly(eventId.toString());
        assertThat(pendingCount()).isZero();
    }

    /**
     * Scenario: crash after Kafka acknowledged the send but before published_at was committed.
     * The event is sent again (at-least-once) with the same eventId, which consumers use to deduplicate.
     */
    @Test
    void redeliveryAfterCrashBetweenSendAndMarkKeepsTheSameEventId() {
        UUID eventId = appendInTransaction("o-3", new SampleEvent("o-3", "PAID"));
        assertThat(relay.publishPending()).isEqualTo(1);

        // Simulate the lost "mark as published" update.
        jdbcTemplate.update("UPDATE messaging.outbox_events SET published_at = NULL WHERE id = ?", eventId);
        assertThat(relay.publishPending()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = consume(2, Duration.ofSeconds(10));
        assertThat(records).hasSize(2);
        assertThat(records).extracting(r -> header(r, "eventId"))
                .containsExactly(eventId.toString(), eventId.toString());
    }

    @Test
    void eventsArePublishedInTheOrderTheyOccurred() {
        UUID first = appendInTransaction("o-4", new SampleEvent("o-4", "PAYMENT_PENDING"));
        UUID second = appendInTransaction("o-4", new SampleEvent("o-4", "PAID"));

        assertThat(relay.publishPending()).isEqualTo(2);

        assertThat(consume(2, Duration.ofSeconds(10))).extracting(r -> header(r, "eventId"))
                .containsExactly(first.toString(), second.toString());
    }

    private UUID appendInTransaction(String key, Object event) {
        return transactionTemplate.execute(status -> outboxWriter.append(topic, key, event));
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messaging.outbox_events WHERE published_at IS NULL", Integer.class);
    }

    private List<ConsumerRecord<String, String>> consume(int expected, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
