package com.orderflow.platform.messaging.api;

import java.util.UUID;

/**
 * Public API of the messaging platform (ADR-0003). Business modules call it from their adapters only.
 */
public interface OutboxWriter {

    /**
     * Stores {@code event} (serialized as JSON) in the outbox, in the caller's transaction.
     * It is published to Kafka asynchronously, at least once, after that transaction commits.
     *
     * @param topic Kafka topic
     * @param key   record key, normally the aggregate ID (preserves per-aggregate order)
     * @return the event ID, sent as the {@code eventId} header; consumers deduplicate on it
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    UUID append(String topic, String key, Object event);
}
