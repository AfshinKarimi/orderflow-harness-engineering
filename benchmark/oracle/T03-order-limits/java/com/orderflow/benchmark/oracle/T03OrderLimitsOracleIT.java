package com.orderflow.benchmark.oracle;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.CreateOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** T03: at most 20 lines and a total of at most 10,000.00 per order, on every way an order is created. */
@OracleSpringBootTest
class T03OrderLimitsOracleIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreateOrderUseCase createOrder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twentyOneLinesAreRejected() throws Exception {
        String customer = customer();

        MvcResult result = postOrder(customer, lines(21, "1.00"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).contains("20");
        assertThat(ordersOf(customer)).isZero();
    }

    @Test
    void totalAboveTenThousandIsRejected() throws Exception {
        String customer = customer();

        MvcResult result = postOrder(customer, lines(1, "10000.01"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).containsPattern("10[,. ]?000");
        assertThat(ordersOf(customer)).isZero();
    }

    @Test
    void limitsApplyOutsideTheRestApi() {
        String customer = customer();
        List<CreateOrderCommand.Line> tooMany = Collections.nCopies(21,
                new CreateOrderCommand.Line("p-1", 1, BigDecimal.ONE));
        List<CreateOrderCommand.Line> tooExpensive = List.of(
                new CreateOrderCommand.Line("p-1", 1, new BigDecimal("10000.01")));

        assertThatThrownBy(() -> createOrder.create(new CreateOrderCommand(customer, "EUR", tooMany)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> createOrder.create(new CreateOrderCommand(customer, "EUR", tooExpensive)))
                .isInstanceOf(RuntimeException.class);
        assertThat(ordersOf(customer)).isZero();
    }

    static String customer() {
        return "t03-" + UUID.randomUUID();
    }

    static String lines(int count, String unitPrice) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"productId\":\"p-" + i + "\",\"quantity\":1,\"unitPrice\":" + unitPrice + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private MvcResult postOrder(String customer, String lines) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customer + "\",\"currency\":\"EUR\",\"lines\":" + lines + "}"))
                .andReturn();
    }

    private int ordersOf(String customer) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM orders.orders WHERE customer_id = ?",
                Integer.class, customer);
    }
}
