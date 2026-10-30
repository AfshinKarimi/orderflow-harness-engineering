package com.orderflow.platform.messaging.api;

import java.util.UUID;

/**
 * Consumer-side deduplication store (ADR-0003 §5). Business modules call it from their adapters only.
 */
public interface ProcessedEvents {

    /**
     * Records that {@code consumer} handled {@code eventId}, in the caller's transaction.
     * Call it before the business change in the same transaction: if that transaction rolls back,
     * the record disappears with it and a retry is processed normally.
     *
     * @return {@code true} if this is the first delivery; {@code false} for a duplicate (skip the business change)
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    boolean markProcessed(UUID eventId, String consumer);
}
