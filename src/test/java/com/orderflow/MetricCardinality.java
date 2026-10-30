package com.orderflow;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against metric tags that grow with traffic (one time series per order, event, ...).
 * See docs/operations/observability.md.
 */
public final class MetricCardinality {

    private static final Pattern UUID_VALUE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PER_ENTITY_TAG_KEYS =
            Set.of("orderId", "customerId", "eventId", "attemptId", "productId", "amount", "key", "message");

    private MetricCardinality() {
    }

    public static void assertNoPerEntityTags(MeterRegistry registry) {
        List<Meter.Id> ids = registry.getMeters().stream().map(Meter::getId).toList();
        assertThat(ids).isNotEmpty();
        for (Meter.Id id : ids) {
            for (Tag tag : id.getTags()) {
                assertThat(UUID_VALUE.matcher(tag.getValue()).matches())
                        .as("tag %s=%s on %s is an ID", tag.getKey(), tag.getValue(), id.getName()).isFalse();
                assertThat(PER_ENTITY_TAG_KEYS)
                        .as("tag key %s on %s", tag.getKey(), id.getName()).doesNotContain(tag.getKey());
            }
        }
    }
}
