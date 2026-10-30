package com.orderflow.platform.messaging.inbox;

import com.orderflow.platform.messaging.api.ProcessedEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    JdbcProcessedEvents(JdbcTemplate jdbcTemplate, Clock clock, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * ON CONFLICT DO NOTHING: a concurrent delivery of the same event waits on the primary key until the first
     * transaction finishes, then inserts nothing (that one committed) or proceeds (that one rolled back).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        int inserted = jdbcTemplate.update("""
                        INSERT INTO messaging.processed_events (event_id, consumer, processed_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (event_id, consumer) DO NOTHING""",
                eventId, consumer, Timestamp.from(clock.instant()));
        // Registered on every call (cheap lookup) so the series exists at 0 before the first duplicate; a series
        // that first appears at 1 shows no increase() in Prometheus. Tag: consumer name, never the event ID.
        Counter duplicates = Counter.builder("orderflow.events.duplicates.skipped")
                .description("Consumed events skipped because this consumer already processed them")
                .tag("consumer", consumer)
                .register(meterRegistry);
        if (inserted == 0) {
            duplicates.increment();
        }
        return inserted == 1;
    }
}
