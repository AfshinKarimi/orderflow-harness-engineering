# Runbook

How to troubleshoot OrderFlow's event-driven payment processing. Metrics, dashboard and alerts:
[observability.md](observability.md). Design background: [ADR-0002](../adr/0002-event-driven-payment-workflow.md),
[ADR-0003](../adr/0003-transactional-outbox-and-kafka.md).

Commands are for the local stack (`docker compose`); in other environments run the same SQL and Kafka tools
against that environment. All of them were run against the local stack on 2026-10-30.

## Tools

```bash
# SQL
docker compose exec postgres psql -U orderflow -d orderflow

# Logs. Local (text): "[<correlationId> <eventId>]" follows the thread name.
grep '<eventId or correlationId>' app.log
# Prod (one JSON object per line; correlationId, eventId, eventType are top-level fields)
jq -c --arg id '<eventId>' 'select(.eventId == $id) | {"@timestamp", level, correlationId, eventType, message}' app.log

# Dead-letter topic: IDs and failure cause only. --formatter-property print.value=false keeps the payload out of
# your terminal: it may contain payment details.
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orderflow.payment.events.DLT --from-beginning --timeout-ms 5000 \
  --formatter-property print.offset=true --formatter-property print.headers=true --formatter-property print.value=false \
  --formatter-property headers.separator=$'\n' 2>/dev/null \
  | LC_ALL=C grep -a -E '^(Offset|eventId|eventType|correlationId|kafka_dlt-exception-cause-fqcn|kafka_dlt-exception-message):'

# Consumer position and lag of the order module
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group orderflow-order
```

Follow one business transaction by its **correlation ID**: it is returned to API callers in `X-Correlation-Id`,
stored with each outbox event (`messaging.outbox_events.correlation_id`), sent as the `correlationId` Kafka header,
and present on every consumer log line. Kafka's own client logs also say "correlation id": that is an unrelated
protocol counter.

**Never** paste payloads, failure reasons or DLT record values into tickets or chats: they may contain payment details.

## Failed payments

Two different things look like "failed payments":

| Symptom | Meaning |
|---|---|
| `OrderFlowPaymentFailureRatioHigh`; "Payment results / min" shows `failed` | Payments are **declined**. The events were processed correctly; orders are `PAYMENT_FAILED`. Usually a provider issue, not an OrderFlow fault. |
| `OrderFlowEventsDeadLettered`; "Dead-lettered" stat above 0; order stuck in `PAYMENT_PENDING` | A payment result **could not be processed**. The order did not change. Needs action. |

**Diagnose a stuck order:**

1. Order state:
   ```sql
   SELECT id, status, payment_attempt_id, version FROM orders.orders WHERE id = '<orderId>';
   ```
2. Did a result arrive? Search the logs for the order ID. `Payment result … : APPLIED` means it was processed;
   `Processing failed at <topic>-<partition>@<offset>` and `Moved … to orderflow.payment.events.DLT` mean it failed.
   Take the `eventId` from that line.
3. Read the DLT (command above). `kafka_dlt-exception-cause-fqcn` is the root cause; the full message is in the
   `Processing failed` log line for that `eventId`.

| Cause | Meaning | Action |
|---|---|---|
| `InvalidOrderStateException` "No payment was requested" | Result for an order that never requested payment | Contract or producer bug: escalate to the payment module owner |
| `InvalidOrderStateException` "already failed and cannot have succeeded" (or the reverse) | Contradictory results for one attempt | Do **not** fix the order by hand without the payment provider's record: escalate |
| `JacksonException` / `IllegalArgumentException` | Unreadable payload, missing header or missing `orderId`/`attemptId` (the log line names the field) | Producer bug; fix the producer, then republish a corrected event |
| `OrderNotFoundException`, database exceptions (after 3 attempts) | Transient or ordering problem | Fix the cause, then republish the original event (same `eventId`) |

**Republishing is safe:** the consumer deduplicates on `eventId`. An event that failed was rolled back completely and
is not in `messaging.processed_events`, so republishing it applies it once. There is no replay tool yet
(open point in [architecture.md](../architecture/architecture.md)).

## Stuck outbox events

**Symptoms:** `OrderFlowOutboxStuck` or `OrderFlowEventDelayHigh`; "Pending events" stays above 0; consumers
never see a change that was committed.

1. What is pending, how often it was tried, and why it failed:
   ```sql
   SELECT id, event_type, attempts, left(last_error, 80), now() - occurred_at AS age
   FROM messaging.outbox_events WHERE published_at IS NULL ORDER BY seq;
   ```
2. Read the pattern:

| Observation | Meaning | Action |
|---|---|---|
| All pending rows have `last_error` like `KafkaException: Send failed`; "Publish failures" rising | Kafka unreachable | Restore Kafka. The relay retries every second; nothing is lost. Watch pending fall to 0. |
| One old row with high `attempts`, the rest wait behind it, Kafka healthy | One event cannot be sent (e.g. too large, topic not allowed); it blocks later events by design | Fix the cause (topic/ACL/size). As a last resort, after agreeing with the consuming team, mark it published and publish a corrected event |
| `attempts = 0`, `last_error` empty, rows ageing | The relay is not running | Check the application is up and `orderflow.outbox.relay.enabled` is not `false`; look for `OutboxRelay` errors in the logs |

3. Relay warnings carry the event's correlation ID: `Outbox event <id> (<type>) not published, will retry: …`.

## Duplicate message processing

**Duplicates are expected:** delivery is at least once (ADR-0003). The outbox resends after a crash between send
and `published_at`, and Kafka redelivers after a consumer crash before the offset commit. The consumer must apply
each event once.

**Confirm duplicates are handled.** The "Duplicates skipped" panel (`orderflow_events_duplicates_skipped_total`,
per consumer) counts every skipped redelivery. A low, steady rate is normal; correlate spikes with restarts and the
"Consumer lag" panel. For one event:

1. Logs: the second delivery of an `eventId` ends with `: DUPLICATE_EVENT`. That is correct behaviour.
   ```bash
   grep '<eventId>' app.log   # expect one APPLIED (or NO_CHANGE) and then DUPLICATE_EVENT lines
   ```
2. One deduplication row per event and consumer:
   ```sql
   SELECT event_id, consumer, processed_at FROM messaging.processed_events WHERE event_id = '<eventId>';
   ```
3. Was it resent by the outbox? `attempts > 1` on a published row means yes:
   ```sql
   SELECT id, attempts, published_at FROM messaging.outbox_events WHERE id = '<eventId>';
   ```

**Suspect a business operation was applied twice?**

| Check | Expected |
|---|---|
| Two log lines `: APPLIED` for the **same** `eventId` | Never. If seen, check that `processed_events` has the row and that the consumer name did not change |
| `APPLIED` for **different** `eventId`s and the same order and attempt | Two distinct events carried the same result: the producer sent it twice under new IDs. The domain makes the second a no-op (`NO_CHANGE`); escalate to the producer |
| Order `version` jumped by more than one per result | Look for other writers of `orders.orders` |

A large number of duplicates usually means consumers are restarting or rebalancing. Check lag and members with
`kafka-consumer-groups.sh --describe` (above) and the "Consumer lag" panel.
