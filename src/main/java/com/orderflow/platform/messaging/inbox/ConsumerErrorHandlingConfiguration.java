package com.orderflow.platform.messaging.inbox;

import com.orderflow.shared.domain.DomainException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.util.List;

/**
 * Error handling for all Kafka listeners (ADR-0003). A failed record is retried in place (its offset is not
 * committed); after the last attempt it is published to {@code <topic>.DLT} with the exception in its headers,
 * so one bad record cannot block its partition. Deterministic failures skip the retries.
 */
@Configuration(proxyBeanMethods = false)
class ConsumerErrorHandlingConfiguration {

    static final String DLT_SUFFIX = ".DLT";

    /** Retrying cannot fix a broken business rule, unreadable JSON or a missing header. */
    private static final List<Class<? extends Exception>> NOT_RETRYABLE =
            List.of(DomainException.class, JacksonException.class, IllegalArgumentException.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate, MeterRegistry meterRegistry,
                                          @Value("${orderflow.kafka.consumer.attempts:3}") int attempts,
                                          @Value("${orderflow.kafka.consumer.backoff:1s}") Duration backoff) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(backoff.toMillis(), attempts - 1L));
        NOT_RETRYABLE.forEach(errorHandler::addNotRetryableExceptions);
        errorHandler.setRetryListeners(new FailureReportingRetryListener(meterRegistry));
        return errorHandler;
    }

    /**
     * Logs and counts every failed delivery attempt and every record moved to the DLT (Spring Kafka logs neither).
     * Metric tags have a fixed set of values: topic, exception class name, retryable. Never keys, IDs or messages.
     * Log lines carry the record's correlationId/eventId/eventType and position, never its payload.
     */
    static final class FailureReportingRetryListener implements RetryListener {

        private static final Logger LOG = LoggerFactory.getLogger(FailureReportingRetryListener.class);

        private final MeterRegistry registry;

        FailureReportingRetryListener(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
            Throwable cause = unwrap(exception);
            boolean retryable = NOT_RETRYABLE.stream().noneMatch(type -> type.isInstance(cause));
            withContext(record, () -> LOG.warn("Processing failed at {} (attempt {}, retryable={}): {}",
                    position(record), deliveryAttempt, retryable, describe(cause)));
            Counter.builder("orderflow.events.processing.failures")
                    .description("Failed attempts to process a consumed event")
                    .tag("topic", record.topic())
                    .tag("exception", cause.getClass().getSimpleName())
                    .tag("retryable", String.valueOf(retryable))
                    .register(registry)
                    .increment();
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
            withContext(record, () -> LOG.error("Moved {} to {}{} after: {}",
                    position(record), record.topic(), DLT_SUFFIX, describe(unwrap(exception))));
            Counter.builder("orderflow.events.dead.lettered")
                    .description("Consumed events moved to the dead-letter topic; each needs investigation")
                    .tag("topic", record.topic())
                    .register(registry)
                    .increment();
        }

        /** Parser messages quote input values (e.g. card numbers), so only the exception type is logged. */
        private static String describe(Throwable cause) {
            String type = cause.getClass().getSimpleName();
            return cause instanceof JacksonException ? type + " (message omitted: may contain payload)"
                    : type + ": " + cause.getMessage();
        }

        private static String position(ConsumerRecord<?, ?> record) {
            return record.topic() + "-" + record.partition() + "@" + record.offset();
        }

        /** The error handler may run after the interceptor cleared the context, so set it again. */
        private static void withContext(ConsumerRecord<?, ?> record, Runnable log) {
            RecordLoggingContext.put(record);
            try {
                log.run();
            } finally {
                RecordLoggingContext.clear();
            }
        }

        private static Throwable unwrap(Exception exception) {
            return exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                    ? exception.getCause()
                    : exception;
        }
    }
}
