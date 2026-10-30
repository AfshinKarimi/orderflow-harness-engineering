-- Consumer-side deduplication (ADR-0003 §5). A row is inserted in the same transaction as the business
-- change it guards; the primary key makes a second delivery of the same event detectable.
CREATE TABLE messaging.processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (event_id, consumer)
);
