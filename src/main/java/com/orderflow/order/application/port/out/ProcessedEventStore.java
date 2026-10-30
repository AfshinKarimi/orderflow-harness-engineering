package com.orderflow.order.application.port.out;

import java.util.UUID;

/** Deduplication state for events consumed by the order module. */
public interface ProcessedEventStore {

    /**
     * Records the event in the current transaction.
     *
     * @return {@code false} if it was already processed
     */
    boolean markProcessed(UUID eventId);
}
