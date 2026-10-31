package com.orderflow.benchmark.oracle;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T08: OrderPlaced on orderflow.order.events via the outbox; the payment module keeps its own copy. */
@OracleSpringBootTest(properties = {"orderflow.outbox.relay.enabled=true", "orderflow.outbox.relay.interval=200ms"})
class T08OrderPlacedOracleIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaContainer kafka;

    @Test
    void createdOrderIsPublished() throws Exception {
        UUID order = create("t08-" + UUID.randomUUID());

        ConsumerRecord<String, String> record = awaitEvent(order);

        assertThat(OrderEventsOracle.header(record, "eventType")).isEqualTo("OrderPlaced");
        assertThat(UUID.fromString(OrderEventsOracle.header(record, "eventId"))).isNotNull();
        JsonNode payload = jsonMapper.readTree(record.value());
        assertThat(payload.path("orderId").asString()).isEqualTo(order.toString());
        assertThat(payload.path("customerId").asString()).startsWith("t08-");
        assertThat(payload.path("currency").asString()).isEqualTo("EUR");
        assertThat(payload.path("totalAmount").decimalValue()).isEqualByComparingTo("39.98");
        assertThat(Instant.parse(payload.path("placedAt").asString())).isNotNull();
    }

    @Test
    void paymentModuleStoresTheOrder() throws Exception {
        UUID order = create("t08-" + UUID.randomUUID());

        Map<String, Object> row = await().atMost(TIMEOUT).until(() -> awaitingPayment(order), r -> r != null);

        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat(new BigDecimal(row.get("total_amount").toString())).isEqualByComparingTo("39.98");
        assertThat(row.get("placed_at")).isNotNull();
        assertThat(row.get("received_at")).isNotNull();
    }

    @Test
    void eventSurvivesKafkaOutage() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        UUID order;
        try {
            order = create("t08-" + UUID.randomUUID());
            Thread.sleep(2000); // the relay tries and fails while the broker is unreachable
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        assertThat(awaitEvent(order)).isNotNull();
    }

    @Test
    void duplicateEventIsStoredOnce() throws Exception {
        UUID order = create("t08-" + UUID.randomUUID());
        ConsumerRecord<String, String> original = awaitEvent(order);
        await().atMost(TIMEOUT).until(() -> awaitingPayment(order) != null);

        // Redeliver the identical record (same eventId), then a marker event; the topic has one partition, so once
        // the marker is stored the duplicate before it has been processed.
        ProducerRecord<String, String> duplicate = new ProducerRecord<>(OrderEventsOracle.TOPIC, original.key(),
                original.value());
        original.headers().forEach(header -> duplicate.headers().add(header));
        kafkaTemplate.send(duplicate).join();
        UUID marker = create("t08-" + UUID.randomUUID());
        await().atMost(TIMEOUT).until(() -> awaitingPayment(marker) != null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment.orders_awaiting_payment WHERE order_id = ?", Integer.class, order))
                .isEqualTo(1);
    }

    private UUID create(String customer) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(OrdersOracle.ORDER_JSON.formatted(customer)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).path("id").asString());
    }

    private ConsumerRecord<String, String> awaitEvent(UUID order) {
        return OrderEventsOracle.find(kafka.getBootstrapServers(), r -> order.toString().equals(r.key()), TIMEOUT)
                .orElseThrow(() -> new AssertionError("no event for order " + order + " on "
                        + OrderEventsOracle.TOPIC));
    }

    private Map<String, Object> awaitingPayment(UUID order) {
        return jdbcTemplate.queryForList("SELECT * FROM payment.orders_awaiting_payment WHERE order_id = ?", order)
                .stream().findFirst().orElse(null);
    }
}
