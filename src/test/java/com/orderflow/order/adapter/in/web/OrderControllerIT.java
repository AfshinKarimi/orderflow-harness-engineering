package com.orderflow.order.adapter.in.web;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import com.orderflow.order.application.port.in.CreateOrderUseCase;
import com.orderflow.order.application.port.in.GetOrderUseCase;
import com.orderflow.order.application.port.in.OrderNotFoundException;
import com.orderflow.order.application.port.in.OrderResult;
import com.orderflow.order.domain.InvalidOrderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerIT {

    private static final UUID ORDER_ID = UUID.fromString("0b6f5a52-3c4e-4d0a-9a43-6d8f1f0e2a11");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private GetOrderUseCase getOrderUseCase;

    @Test
    void returnsExistingOrder() throws Exception {
        when(getOrderUseCase.get(ORDER_ID)).thenReturn(new OrderResult(
                ORDER_ID, "c-1", "CREATED", "EUR", new BigDecimal("3.00"),
                List.of(new OrderResult.Line("p-1", 2, new BigDecimal("1.50"), new BigDecimal("3.00"))),
                Instant.parse("2026-10-30T12:00:00Z")));

        mockMvc.perform(get("/api/v1/orders/" + ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(3.00))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(3.00));
    }

    @Test
    void returns404ProblemForUnknownOrder() throws Exception {
        when(getOrderUseCase.get(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        mockMvc.perform(get("/api/v1/orders/" + ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Order not found"))
                .andExpect(jsonPath("$.detail").value("Order " + ORDER_ID + " not found"));
    }

    @Test
    void returns400ForMalformedOrderId() throws Exception {
        mockMvc.perform(get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(getOrderUseCase);
    }

    @Test
    void createsOrderAndReturns201WithLocationAndBody() throws Exception {
        when(createOrderUseCase.create(any())).thenReturn(new OrderResult(
                ORDER_ID, "c-1", "CREATED", "EUR", new BigDecimal("39.98"),
                List.of(new OrderResult.Line("p-1", 2, new BigDecimal("19.99"), new BigDecimal("39.98"))),
                Instant.parse("2026-10-30T12:00:00Z")));

        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-1","currency":"EUR",
                         "lines":[{"productId":"p-1","quantity":2,"unitPrice":19.99}]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + ORDER_ID))
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.totalAmount").value(39.98))
                .andExpect(jsonPath("$.lines[0].productId").value("p-1"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(39.98))
                .andExpect(jsonPath("$.createdAt").value("2026-10-30T12:00:00Z"));

        verify(createOrderUseCase).create(new CreateOrderCommand("c-1", "EUR",
                List.of(new CreateOrderCommand.Line("p-1", 2, new BigDecimal("19.99")))));
    }

    @Test
    void rejectsMissingFieldsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"currency":"eur","lines":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'currency')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'lines')]").exists());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsInvalidLineValues() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-1","currency":"EUR",
                         "lines":[{"productId":"","quantity":0,"unitPrice":-1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].productId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].quantity')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0].unitPrice')]").exists());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsNullLineInsteadOfFailingWith500() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-1","currency":"EUR","lines":[null]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'lines[0]')]").exists());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createOrderUseCase);
    }

    @Test
    void mapsDomainRuleViolationTo400ProblemDetail() throws Exception {
        when(createOrderUseCase.create(any()))
                .thenThrow(new InvalidOrderException("All order lines must use the same currency"));

        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"customerId":"c-1","currency":"EUR",
                         "lines":[{"productId":"p-1","quantity":1,"unitPrice":1.00}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Business rule violated"))
                .andExpect(jsonPath("$.detail").value("All order lines must use the same currency"));
    }
}
