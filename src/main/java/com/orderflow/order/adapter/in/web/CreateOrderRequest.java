package com.orderflow.order.adapter.in.web;

import com.orderflow.order.application.port.in.CreateOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Shape validation only; business rules are enforced again in the domain. */
record CreateOrderRequest(
        @NotBlank @Size(max = 100) String customerId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid LineRequest> lines) {

    record LineRequest(
            @NotBlank @Size(max = 100) String productId,
            @NotNull @Min(1) Integer quantity,
            @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal unitPrice) {
    }

    CreateOrderCommand toCommand() {
        return new CreateOrderCommand(customerId, currency, lines.stream()
                .map(line -> new CreateOrderCommand.Line(line.productId(), line.quantity(), line.unitPrice()))
                .toList());
    }
}
