package com.orderflow.platform.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation ID that ties together the logs of one HTTP request, the events it causes and their consumers
 * (docs/operations/observability.md). It lives in the logging context (MDC) under {@link #MDC_KEY}.
 */
public final class CorrelationId {

    /** HTTP request and response header. */
    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** Kafka record header and MDC key. */
    public static final String MDC_KEY = "correlationId";

    /** Restricts caller-supplied IDs, so they cannot inject line breaks or markup into logs. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private CorrelationId() {
    }

    /** @return the given ID if it is well-formed, otherwise a new one */
    public static String acceptOrCreate(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }

    /** @return the correlation ID of the current thread, or {@code null} */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
