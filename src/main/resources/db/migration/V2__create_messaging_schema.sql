-- Platform messaging module owns the "messaging" schema (ADR-0003: transactional outbox).
CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE messaging.outbox_events (
    id           UUID          PRIMARY KEY,           -- also the eventId header: consumers deduplicate on it
    seq          BIGINT        GENERATED ALWAYS AS IDENTITY, -- publish order (timestamps can tie)
    topic        VARCHAR(200)  NOT NULL,
    msg_key      VARCHAR(200)  NOT NULL,              -- aggregate ID: keeps per-aggregate order in Kafka
    event_type   VARCHAR(200)  NOT NULL,
    payload      JSONB         NOT NULL,
    occurred_at  TIMESTAMPTZ   NOT NULL,
    published_at TIMESTAMPTZ,                         -- NULL = pending
    attempts     INT           NOT NULL DEFAULT 0,
    last_error   VARCHAR(1000)
);

CREATE INDEX outbox_events_pending_idx ON messaging.outbox_events (seq) WHERE published_at IS NULL;
