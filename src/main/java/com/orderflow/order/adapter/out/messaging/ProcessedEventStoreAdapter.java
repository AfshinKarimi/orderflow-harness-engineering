package com.orderflow.order.adapter.out.messaging;

import com.orderflow.order.application.port.out.ProcessedEventStore;
import com.orderflow.platform.messaging.api.ProcessedEvents;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ProcessedEventStoreAdapter implements ProcessedEventStore {

    /** Deduplication scope: events consumed by the order module. */
    static final String CONSUMER = "order";

    private final ProcessedEvents processedEvents;

    ProcessedEventStoreAdapter(ProcessedEvents processedEvents) {
        this.processedEvents = processedEvents;
    }

    @Override
    public boolean markProcessed(UUID eventId) {
        return processedEvents.markProcessed(eventId, CONSUMER);
    }
}
