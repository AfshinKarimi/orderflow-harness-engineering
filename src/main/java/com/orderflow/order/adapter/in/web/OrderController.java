package com.orderflow.order.adapter.in.web;

import com.orderflow.order.application.port.in.CreateOrderUseCase;
import com.orderflow.order.application.port.in.GetOrderUseCase;
import com.orderflow.order.application.port.in.OrderResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(OrderController.BASE_PATH)
class OrderController {

    static final String BASE_PATH = "/api/v1/orders";

    private final CreateOrderUseCase createOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;

    OrderController(CreateOrderUseCase createOrderUseCase, GetOrderUseCase getOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
        this.getOrderUseCase = getOrderUseCase;
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResult result = createOrderUseCase.create(request.toCommand());
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + result.id()))
                .body(OrderResponse.from(result));
    }

    @GetMapping("/{orderId}")
    OrderResponse get(@PathVariable UUID orderId) {
        return OrderResponse.from(getOrderUseCase.get(orderId));
    }
}
