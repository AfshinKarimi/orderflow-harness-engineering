package com.orderflow.order;

import com.orderflow.KafkaTestcontainersConfiguration;
import com.orderflow.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end: HTTP → use case → domain → JPA → PostgreSQL (Flyway schema). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
// Benchmark T05: copy of the base test with a shipping address in every request (the address became required).
class CreateOrderIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM orders.orders");
    }

    @Test
    void persistsOrderAndReturns201() throws Exception {
        String location = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-42","currency":"EUR",
                         "shippingAddress":{"street":"Main St 1","city":"Berlin","postalCode":"10115","country":"DE"},
                         "lines":[{"productId":"p-1","quantity":2,"unitPrice":19.99},
                                  {"productId":"p-2","quantity":1,"unitPrice":0.02}]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(40.00))
                .andReturn().getResponse().getHeader("Location");

        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT customer_id, status, currency FROM orders.orders WHERE id = ?", id);
        assertThat(row).containsEntry("customer_id", "c-42")
                .containsEntry("status", "CREATED")
                .containsEntry("currency", "EUR");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders.order_lines WHERE order_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void createdOrderCanBeReadBackViaLocation() throws Exception {
        String location = mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-7","currency":"EUR",
                         "shippingAddress":{"street":"Main St 1","city":"Berlin","postalCode":"10115","country":"DE"},
                         "lines":[{"productId":"p-1","quantity":3,"unitPrice":1.10}]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("c-7"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(3.30))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(1.10));
    }

    @Test
    void invalidRequestPersistsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-42","currency":"EUR",
                         "shippingAddress":{"street":"Main St 1","city":"Berlin","postalCode":"10115","country":"DE"},
                         "lines":[{"productId":"p-1","quantity":1,"unitPrice":10.005}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Amount 10.005 has more than 2 decimal places for EUR"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders.orders", Integer.class)).isZero();
    }
}
