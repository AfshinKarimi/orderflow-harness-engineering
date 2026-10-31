-- Correlation ID of the request that caused the event; sent as the "correlationId" Kafka header.
-- Nullable: events written outside a request (and rows from before this migration) have none.
ALTER TABLE messaging.outbox_events ADD COLUMN correlation_id VARCHAR(64);
