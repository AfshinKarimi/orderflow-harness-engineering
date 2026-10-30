package com.orderflow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orderflow.order.application.port.out.OrderRepository;
import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderId;
import com.orderflow.order.domain.OrderLine;
import com.orderflow.payment.application.event.PaymentEvents;
import com.orderflow.payment.application.event.PaymentFailed;
import com.orderflow.payment.application.event.PaymentSucceeded;
import com.orderflow.platform.messaging.api.OutboxWriter;
import com.orderflow.platform.observability.CorrelationId;
import com.orderflow.shared.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Correlation IDs from the event producer to the consumer's logs, and no payment details in any log line
 * (docs/operations/observability.md). Runs with the scheduled outbox relay enabled.
 * Assertions do not depend on the console format: Spring Boot initializes logging once per JVM, so a property
 * like logging.structured.format.console is ignored when another test context started first.
 */
@SpringBootTest(properties = {
        "orderflow.outbox.relay.enabled=true",
        "orderflow.outbox.relay.interval=200ms"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class LoggingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Test card number; must never appear in logs. */
    private static final String CARD = "4111 1111 1111 1111";

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    private final ListAppender<ILoggingEvent> listenerLog = new ListAppender<>();

    @BeforeEach
    void captureListenerLog() {
        listenerLog.start();
        listenerLogger().addAppender(listenerLog);
    }

    @AfterEach
    void releaseListenerLog() {
        listenerLogger().detachAppender(listenerLog);
    }

    private static Logger listenerLogger() {
        return (Logger) LoggerFactory.getLogger("com.orderflow.order.adapter.in.event.PaymentResultsListener");
    }

    @Test
    void correlationIdTravelsFromTheProducerThroughTheOutboxAndKafkaIntoTheConsumerLogs() {
        Order order = pendingOrder();
        String correlationId = "it-" + UUID.randomUUID().toString().substring(0, 8);

        // The request thread that changes state and appends the event has the correlation ID in its context.
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        UUID eventId;
        try {
            eventId = transactionTemplate.execute(status -> outboxWriter.append(PaymentEvents.TOPIC,
                    order.id().value().toString(),
                    new PaymentSucceeded(order.id().value(), order.currentPaymentAttempt().value())));
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT correlation_id FROM messaging.outbox_events WHERE id = ?",
                String.class, eventId)).isEqualTo(correlationId);

        // Relay → Kafka (header) → listener: the consumer's log event carries the same IDs in its context.
        ILoggingEvent line = await().atMost(TIMEOUT).until(
                () -> listenerLog.list.stream()
                        .filter(e -> e.getFormattedMessage().startsWith("Payment result"))
                        .filter(e -> correlationId.equals(e.getMDCPropertyMap().get(CorrelationId.MDC_KEY)))
                        .findFirst(),
                Optional::isPresent).orElseThrow();
        assertThat(line.getMDCPropertyMap())
                .containsEntry("eventId", eventId.toString())
                .containsEntry("eventType", PaymentEvents.SUCCEEDED);
        assertThat(line.getFormattedMessage()).contains(order.id().value().toString()).contains("APPLIED");
    }

    @Test
    void paymentDetailsNeverReachTheLogs(CapturedOutput output) {
        double deadBefore = deadLettered();

        // 1. Applied normally: the failure reason (free text from the provider) is not logged.
        Order pending = pendingOrder();
        UUID applied = publish(PaymentEvents.FAILED, new PaymentFailed(
                pending.id().value(), pending.currentPaymentAttempt().value(), "Declined: card " + CARD));
        // 2. Business-rule violation (no payment requested): retried 0 times, dead-lettered, error logged.
        Order created = createdOrder();
        UUID rejected = publish(PaymentEvents.FAILED, new PaymentFailed(
                created.id().value(), UUID.randomUUID(), "Declined: card " + CARD));
        // 3. Unreadable payload whose bad value is the card number: parser errors quote input values.
        UUID unreadable = publishRaw(PaymentEvents.FAILED,
                "{\"orderId\":\"" + CARD + "\",\"attemptId\":\"x\",\"reason\":\"" + CARD + "\"}");

        await().atMost(TIMEOUT).until(() -> deadLettered() - deadBefore >= 2
                && "PAYMENT_FAILED".equals(statusOf(pending)));

        String logs = output.getAll();
        assertThat(logs).as("the scenarios were logged").contains(applied.toString(), rejected.toString(),
                unreadable.toString());
        assertThat(logs).doesNotContain(CARD).doesNotContain(CARD.replace(" ", "")).doesNotContain("Declined");
    }

    private Order pendingOrder() {
        Order order = newOrder();
        order.requestPayment();
        orderRepository.save(order);
        return order;
    }

    private Order createdOrder() {
        Order order = newOrder();
        orderRepository.save(order);
        return order;
    }

    private static Order newOrder() {
        return Order.create(OrderId.newId(), "c-1",
                List.of(new OrderLine("p-1", 1, Money.of(new BigDecimal("5.00"), "EUR"))), Instant.now());
    }

    private UUID publish(String type, Object event) {
        return publishRaw(type, jsonMapper.writeValueAsString(event));
    }

    private UUID publishRaw(String type, String payload) {
        UUID eventId = UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>(PaymentEvents.TOPIC, "k", payload);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
        return eventId;
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders.orders WHERE id = ?", String.class,
                order.id().value());
    }

    private double deadLettered() {
        return meterRegistry.find("orderflow.events.dead.lettered").counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
