package com.orderflow.platform.messaging.inbox;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

/**
 * Sets the record's logging context ({@link RecordLoggingContext}) for every listener; cleared after the record.
 * Applied by Spring Boot to the listener container factory.
 */
@Component
class LoggingContextRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        RecordLoggingContext.put(record);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        RecordLoggingContext.clear();
    }
}
