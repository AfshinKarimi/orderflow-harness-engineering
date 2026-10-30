package com.orderflow.order.application.service;

import com.orderflow.order.application.port.out.OrderMetrics;

import java.util.ArrayList;
import java.util.List;

/** Fake {@link OrderMetrics} that records what was reported, in order. */
class RecordingOrderMetrics implements OrderMetrics {

    final List<String> recorded = new ArrayList<>();

    @Override
    public void orderCreated() {
        recorded.add("orderCreated");
    }

    @Override
    public void paymentSucceeded() {
        recorded.add("paymentSucceeded");
    }

    @Override
    public void paymentFailed() {
        recorded.add("paymentFailed");
    }
}
