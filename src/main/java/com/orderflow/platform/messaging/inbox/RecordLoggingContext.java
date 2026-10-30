package com.orderflow.platform.messaging.inbox;

import com.orderflow.platform.observability.CorrelationId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

/**
 * Logging context (MDC) of a consumed record: correlationId, eventId and eventType from its headers.
 * Header values come from outside, so only well-formed values are used. The payload is never touched.
 */
final class RecordLoggingContext {

    static final String EVENT_ID = "eventId";
    static final String EVENT_TYPE = "eventType";

    private RecordLoggingContext() {
    }

    static void put(ConsumerRecord<?, ?> record) {
        // Without a (valid) correlation ID, the eventId stands in: stable across retries, so lines can be grouped.
        String correlationId = header(record, CorrelationId.MDC_KEY);
        String eventId = header(record, EVENT_ID);
        MDC.put(CorrelationId.MDC_KEY, CorrelationId.acceptOrCreate(isValid(correlationId) ? correlationId : eventId));
        putIfValid(EVENT_ID, eventId);
        putIfValid(EVENT_TYPE, header(record, EVENT_TYPE));
    }

    static void clear() {
        MDC.remove(CorrelationId.MDC_KEY);
        MDC.remove(EVENT_ID);
        MDC.remove(EVENT_TYPE);
    }

    private static void putIfValid(String key, String value) {
        if (isValid(value)) {
            MDC.put(key, value);
        }
    }

    private static boolean isValid(String value) {
        return value != null && value.equals(CorrelationId.acceptOrCreate(value));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
