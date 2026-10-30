package com.orderflow.order.adapter.in.event;

import com.orderflow.KafkaTestcontainersConfiguration;
import com.orderflow.MetricCardinality;
import com.orderflow.TestcontainersConfiguration;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.Outcome;
import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.domain.PaymentAttemptId;
import com.orderflow.payment.application.event.PaymentEvents;
import com.orderflow.payment.application.event.PaymentSucceeded;
import com.orderflow.shared.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

/**
 * Idempotent consumption of payment results (ADR-0003 §5) through the real listener, PostgreSQL and Kafka.
 * The business side effect is saving the order; spies count use case calls and saves without changing behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentResultsListenerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String FAILURES = "orderflow.events.processing.failures";
    private static final String DEAD_LETTERED = "orderflow.events.dead.lettered";
    private static final String DUPLICATES = "orderflow.events.duplicates.skipped";

    @MockitoSpyBean
    private HandlePaymentResultUseCase handlePaymentResult;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry meterRegistry;

    private Order order;
    private PaymentAttemptId attempt;

    /** Outcomes of use case calls that returned normally, in order (filled on the listener thread). */
    private final List<Outcome> outcomes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void pendingOrder() {
        order = Order.create(OrderId.newId(), "c-1",
                List.of(new OrderLine("p-1", 1, Money.of(new BigDecimal("5.00"), "EUR"))), Instant.now());
        attempt = order.requestPayment();
        orderRepository.save(order);
        clearInvocations(orderRepository, handlePaymentResult);
        outcomes.clear();
        doAnswer(invocation -> {
            Outcome outcome = (Outcome) invocation.callRealMethod();
            outcomes.add(outcome);
            return outcome;
        }).when(handlePaymentResult).handle(any());
    }

    /** Consumer, retry, dead-letter and Kafka client meters carry no event, order or attempt IDs. */
    @AfterEach
    void metricsHaveNoPerEntityTags() {
        MetricCardinality.assertNoPerEntityTags(meterRegistry);
    }

    @Test
    void redeliveredEventIsProcessedOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        double succeededBefore = count("orderflow.orders.payment.results", "result", "succeeded");
        double duplicatesBefore = count(DUPLICATES, "consumer", "order");

        // Same record twice: a producer resend, or a redelivery after a crash before the offset commit.
        publishSucceeded(eventId);
        publishSucceeded(eventId);

        awaitUseCaseCalls(2);
        // DUPLICATE_EVENT (not NO_CHANGE) proves the deduplication store stopped it, not the domain's own idempotency.
        assertThat(outcomes).containsExactly(Outcome.APPLIED, Outcome.DUPLICATE_EVENT);
        assertThat(saveCalls()).as("business side effect happened once").isEqualTo(1);
        assertThat(statusOfOrder()).isEqualTo(OrderStatus.PAID);
        assertThat(processedRows(eventId)).isEqualTo(1);
        assertThat(count("orderflow.orders.payment.results", "result", "succeeded") - succeededBefore)
                .as("counted once").isEqualTo(1.0);
        assertThat(count(DUPLICATES, "consumer", "order") - duplicatesBefore).isEqualTo(1.0);
        // Built-in per-record processing latency from Spring Kafka (with histogram, see application.yml).
        assertThat(meterRegistry.find("spring.kafka.listener").tag("result", "success").timers()).isNotEmpty();
    }

    @Test
    void distinctEventsWithTheSameResultChangeTheOrderOnce() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        double duplicatesBefore = count(DUPLICATES, "consumer", "order");

        publishSucceeded(first);
        publishSucceeded(second);

        awaitUseCaseCalls(2);
        assertThat(outcomes).containsExactly(Outcome.APPLIED, Outcome.NO_CHANGE);
        assertThat(saveCalls()).as("second event is new, but the domain transition is a no-op").isEqualTo(1);
        assertThat(statusOfOrder()).isEqualTo(OrderStatus.PAID);
        assertThat(processedRows(first)).isEqualTo(1);
        assertThat(processedRows(second)).isEqualTo(1);
        assertThat(count(DUPLICATES, "consumer", "order") - duplicatesBefore)
                .as("new event IDs are not duplicates, even with the same result").isZero();
    }

    @Test
    void failedAttemptIsRolledBackAndRetriedSafely() {
        doThrow(new TransientDataAccessResourceException("simulated database failure"))
                .doCallRealMethod()
                .when(orderRepository).save(any());
        UUID eventId = UUID.randomUUID();

        publishSucceeded(eventId);

        await().atMost(TIMEOUT).until(() -> statusOfOrder() == OrderStatus.PAID);
        // 1st attempt: dedup row inserted, save failed, transaction rolled back (dedup row too).
        // 2nd attempt: not seen as a duplicate, so it is applied. Had the dedup row survived, the order would stay
        // PAYMENT_PENDING forever.
        assertThat(useCaseCalls()).isEqualTo(2);
        assertThat(outcomes).as("only the successful attempt returned").containsExactly(Outcome.APPLIED);
        assertThat(saveCalls()).isEqualTo(2);
        assertThat(processedRows(eventId)).isEqualTo(1);
    }

    @Test
    void eventThatKeepsFailingGoesToTheDeadLetterTopicWithoutSideEffects() {
        doThrow(new TransientDataAccessResourceException("database down")).when(orderRepository).save(any());
        UUID eventId = UUID.randomUUID();
        double failuresBefore = count(FAILURES, "exception", "TransientDataAccessResourceException");
        double deadBefore = count(DEAD_LETTERED, "topic", PaymentEvents.TOPIC);

        publishSucceeded(eventId);

        ConsumerRecord<String, String> dead = awaitDeadLetter(eventId);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("database down");
        assertThat(useCaseCalls()).as("3 attempts").isEqualTo(3);
        assertThat(processedRows(eventId)).as("every attempt rolled back").isZero();
        assertThat(statusOfOrder()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(meterRegistry.get(FAILURES).tags("topic", PaymentEvents.TOPIC,
                        "exception", "TransientDataAccessResourceException", "retryable", "true").counter().count()
                - failuresBefore).as("one per attempt").isEqualTo(3.0);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(count(DEAD_LETTERED, "topic", PaymentEvents.TOPIC) - deadBefore).isEqualTo(1.0));
    }

    @Test
    void businessRuleViolationGoesToTheDeadLetterTopicWithoutRetries() {
        UUID eventId = UUID.randomUUID();

        // A payment result for an order that never requested payment breaks the transition rules
        // (InvalidOrderStateException). Retrying cannot fix that, so it is dead-lettered on the first failure.
        Order created = Order.create(OrderId.newId(), "c-2",
                List.of(new OrderLine("p-1", 1, Money.of(BigDecimal.ONE, "EUR"))), Instant.now());
        orderRepository.save(created);
        clearInvocations(handlePaymentResult);
        double failuresBefore = count(FAILURES, "exception", "InvalidOrderStateException");
        double deadBefore = count(DEAD_LETTERED, "topic", PaymentEvents.TOPIC);
        publish(eventId, PaymentEvents.SUCCEEDED, created.id().value(),
                new PaymentSucceeded(created.id().value(), UUID.randomUUID()));

        awaitDeadLetter(eventId);
        assertThat(useCaseCalls()).as("not retried").isEqualTo(1);
        assertThat(processedRows(eventId)).isZero();
        assertThat(meterRegistry.get(FAILURES).tags("topic", PaymentEvents.TOPIC,
                        "exception", "InvalidOrderStateException", "retryable", "false").counter().count()
                - failuresBefore).isEqualTo(1.0);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(count(DEAD_LETTERED, "topic", PaymentEvents.TOPIC) - deadBefore).isEqualTo(1.0));
    }

    @Test
    void eventWithAMissingIdGoesToTheDeadLetterTopicWithoutRetries() {
        UUID eventId = UUID.randomUUID();
        double failuresBefore = count(FAILURES, "exception", "IllegalArgumentException");

        // "" deserializes to a null UUID, like a missing field or JSON null.
        publishRaw(eventId, PaymentEvents.SUCCEEDED, order.id().value(),
                "{\"orderId\":\"\",\"attemptId\":\"" + attempt.value() + "\"}");

        assertThat(header(awaitDeadLetter(eventId), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(useCaseCalls()).as("rejected before the use case").isZero();
        assertThat(meterRegistry.get(FAILURES).tags("topic", PaymentEvents.TOPIC,
                        "exception", "IllegalArgumentException", "retryable", "false").counter().count()
                - failuresBefore).as("one attempt only").isEqualTo(1.0);
        assertThat(statusOfOrder()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    }

    /** Sum over all meters with this name and tag; 0 if none was registered yet. */
    private double count(String name, String tagKey, String tagValue) {
        return meterRegistry.find(name).tag(tagKey, tagValue).counters().stream().mapToDouble(Counter::count).sum();
    }

    private void publishSucceeded(UUID eventId) {
        publish(eventId, PaymentEvents.SUCCEEDED, order.id().value(),
                new PaymentSucceeded(order.id().value(), attempt.value()));
    }

    private void publish(UUID eventId, String type, UUID key, Object event) {
        publishRaw(eventId, type, key, jsonMapper.writeValueAsString(event));
    }

    private void publishRaw(UUID eventId, String type, UUID key, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(PaymentEvents.TOPIC, key.toString(), payload);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private void awaitUseCaseCalls(int expected) {
        await().atMost(TIMEOUT).until(() -> useCaseCalls() >= expected);
    }

    private long useCaseCalls() {
        return mockingDetails(handlePaymentResult).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("handle"))
                .count();
    }

    private long saveCalls() {
        return mockingDetails(orderRepository).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("save"))
                .count();
    }

    private OrderStatus statusOfOrder() {
        return OrderStatus.valueOf(jdbcTemplate.queryForObject(
                "SELECT status FROM orders.orders WHERE id = ?", String.class, order.id().value()));
    }

    private int processedRows(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messaging.processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    private ConsumerRecord<String, String> awaitDeadLetter(UUID eventId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(PaymentEvents.TOPIC + ".DLT"));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (matches.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> {
                    if (eventId.toString().equals(header(record, "eventId"))) {
                        matches.add(record);
                    }
                });
            }
        }
        assertThat(matches).as("dead-lettered record for event " + eventId).hasSize(1);
        return matches.getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
