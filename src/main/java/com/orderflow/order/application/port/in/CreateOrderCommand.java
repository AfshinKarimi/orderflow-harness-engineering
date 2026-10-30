package com.orderflow.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderCommand(String customerId, String currency, List<Line> lines) {

    public record Line(String productId, int quantity, BigDecimal unitPrice) {
    }
}
