package com.orderflow;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.CreateOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Business metrics count only committed work, are exposed for Prometheus, and carry no per-entity tags
 * (docs/operations/observability.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class ObservabilityIT {

    private static final String ORDERS_PLACED = "orderflow.orders.placed";
    private static final String ORDER_JSON = """
            {"customerId":"c-1","currency":"EUR","lines":[{"productId":"p-1","quantity":1,"unitPrice":5.00}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CreateOrderUseCase createOrder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createdOrderIsCounted() throws Exception {
        double before = ordersPlaced();

        createOrderViaApi();

        assertThat(ordersPlaced() - before).isEqualTo(1.0);
    }

    @Test
    void orderRolledBackAfterCreationIsNotCounted() {
        double before = ordersPlaced();

        // The use case succeeds, but the surrounding transaction rolls back (e.g. a later step failed).
        UUID id = transactionTemplate.execute(status -> {
            UUID created = createOrder.create(new CreateOrderCommand("c-1", "EUR",
                    List.of(new CreateOrderCommand.Line("p-1", 1, BigDecimal.ONE)))).id();
            status.setRollbackOnly();
            return created;
        });

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders.orders WHERE id = ?", Integer.class, id))
                .isZero();
        assertThat(ordersPlaced() - before).as("rolled-back work is not counted").isZero();
    }

    @Test
    void prometheusEndpointExposesTheMetricCatalogue() throws Exception {
        createOrderViaApi();

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .contains("orderflow_orders_placed_total{application=\"orderflow\"}")
                .contains("orderflow_orders_payment_results_total{application=\"orderflow\",result=\"succeeded\"}")
                .contains("orderflow_orders_payment_results_total{application=\"orderflow\",result=\"failed\"}")
                .contains("orderflow_outbox_pending{application=\"orderflow\"}")
                .contains("http_server_requests_seconds_bucket{")
                .contains("uri=\"/api/v1/orders\"");
    }

    /** HTTP traffic with real order IDs must not create one time series per order. */
    @Test
    void noMetricIsTaggedWithPerEntityValues() throws Exception {
        String location = createOrderViaApi();
        mockMvc.perform(get(location)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());

        MetricCardinality.assertNoPerEntityTags(meterRegistry);
        assertThat(meterRegistry.find("http.server.requests").tag("uri", "/api/v1/orders/{orderId}").timers())
                .as("URI template, not the concrete ID").isNotEmpty();
    }

    private String createOrderViaApi() throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }

    private double ordersPlaced() {
        return meterRegistry.get(ORDERS_PLACED).counter().count();
    }
}
