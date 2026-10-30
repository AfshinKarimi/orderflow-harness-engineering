package com.orderflow.order.adapter.in.event;

import com.orderflow.order.application.port.in.HandlePaymentResultUseCase;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.Outcome;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.PaymentResult;
import com.orderflow.order.application.port.in.HandlePaymentResultUseCase.PaymentResultCommand;
import com.orderflow.payment.application.event.PaymentEvents;
import com.orderflow.payment.application.event.PaymentFailed;
import com.orderflow.payment.application.event.PaymentSucceeded;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Translates payment-result records into use case calls. Holds no transaction itself: the use case's transaction
 * covers deduplication and the order change, and the offset is committed only after this method returns.
 * Any exception leaves the offset uncommitted, so the record is retried (see ConsumerErrorHandlingConfiguration).
 */
@Component
class PaymentResultsListener {

    static final String GROUP_ID = "orderflow-order";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentResultsListener.class);

    private final HandlePaymentResultUseCase handlePaymentResult;
    private final JsonMapper jsonMapper;

    PaymentResultsListener(HandlePaymentResultUseCase handlePaymentResult, JsonMapper jsonMapper) {
        this.handlePaymentResult = handlePaymentResult;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = PaymentEvents.TOPIC, groupId = GROUP_ID)
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        UUID eventId = UUID.fromString(requiredHeader(record, "eventId"));
        String eventType = requiredHeader(record, "eventType");
        PaymentResultCommand command = switch (eventType) {
            case PaymentEvents.SUCCEEDED -> {
                PaymentSucceeded event = jsonMapper.readValue(record.value(), PaymentSucceeded.class);
                yield new PaymentResultCommand(eventId, event.orderId(), event.attemptId(), PaymentResult.SUCCEEDED);
            }
            case PaymentEvents.FAILED -> {
                PaymentFailed event = jsonMapper.readValue(record.value(), PaymentFailed.class);
                yield new PaymentResultCommand(eventId, event.orderId(), event.attemptId(), PaymentResult.FAILED);
            }
            default -> null;
        };
        if (command == null) {
            LOG.debug("Ignoring event of type {}", eventType);
            return;
        }
        requireField(command.orderId(), "orderId", record);
        requireField(command.attemptId(), "attemptId", record);
        Outcome outcome = handlePaymentResult.handle(command);
        // eventId, eventType and correlationId are in the logging context. Never log the payload: a failure
        // reason may contain provider or card details (docs/operations/observability.md).
        LOG.info("Payment result {} for order {} attempt {}: {}",
                command.result(), command.orderId(), command.attemptId(), outcome);
    }

    /**
     * JSON null, a missing field and {@code ""} all deserialize to null; without this check the domain would throw
     * a NullPointerException, which is retried although the event can never succeed.
     *
     * @throws IllegalArgumentException (not retried, sent to the DLT) if the field is missing
     */
    private static void requireField(UUID value, String field, ConsumerRecord<String, String> record) {
        if (value == null) {
            throw new IllegalArgumentException("Missing field '" + field + "' in event at " + record.topic()
                    + "-" + record.partition() + "@" + record.offset());
        }
    }

    /** @throws IllegalArgumentException (not retried) if the header is missing */
    private static String requiredHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            throw new IllegalArgumentException("Missing header '" + name + "' on " + record.topic()
                    + "-" + record.partition() + "@" + record.offset());
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
