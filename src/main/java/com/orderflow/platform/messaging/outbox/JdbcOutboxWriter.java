package com.orderflow.platform.messaging.outbox;

import com.orderflow.platform.messaging.api.OutboxWriter;
import com.orderflow.platform.observability.CorrelationId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
class JdbcOutboxWriter implements OutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcOutboxWriter(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * MANDATORY: the event must be written in the same transaction as the business change it announces.
     * The current correlation ID (if any) is stored with it and travels to consumers as a Kafka header.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String key, Object event) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO messaging.outbox_events
                            (id, topic, msg_key, event_type, payload, occurred_at, correlation_id)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
                eventId, topic, key, event.getClass().getSimpleName(), jsonMapper.writeValueAsString(event),
                Timestamp.from(clock.instant()), CorrelationId.current());
        return eventId;
    }
}
