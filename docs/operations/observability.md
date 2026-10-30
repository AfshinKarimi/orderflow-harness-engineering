# Observability

Spring Boot Actuator with Micrometer. Scrape endpoint: `/actuator/prometheus` (all metrics also browsable at `/actuator/metrics`).
Every metric carries the common tag `application=orderflow`.

## Exposure

| Profile | Port | Endpoints |
|---|---|---|
| `local` | 8080 (same as the API) | `health`, `info`, `metrics`, `prometheus` |
| `prod` | **8081** (`MANAGEMENT_PORT`), separate from the API | same; the port must be reachable only from inside the network (no Spring Security yet) |

In `prod`, liveness and readiness probes are also on the management port: `:8081/actuator/health/{liveness,readiness}`.

## Metric catalogue

### Business

| Metric (Prometheus name) | Type | Tags | Why it matters |
|---|---|---|---|
| `orderflow_orders_placed_total` | counter | none | Business throughput. Zero while HTTP traffic is normal means orders are being rejected or failing. **Primary business alert.** |
| `orderflow_orders_payment_results_total` | counter | `result` = `succeeded` \| `failed` | Payment health. A rising failure share points at the payment provider or a pricing bug. Counted when the result *changes* an order, so redelivered events cannot inflate it. |

Both count **only after the transaction commits** (`MicrometerOrderMetrics`), so rolled-back and retried work is never counted.
They are reported through the `OrderMetrics` port, so the application layer decides what counts as a business event.
Named `placed`, not `created`: Prometheus reserves the `_created` suffix and would drop it.

### Event processing

| Metric | Type | Tags | Why it matters |
|---|---|---|---|
| `orderflow_events_processing_failures_total` | counter | `topic`, `exception` (class name), `retryable` | Why consumption fails. `retryable=true` (database, timeouts) means infrastructure trouble; `retryable=false` (bad JSON, business-rule violation) means a contract or logic bug. One increment per failed attempt. |
| `orderflow_events_duplicates_skipped_total` | counter | `consumer` | Redeliveries recognised by deduplication and skipped. Expected at a low rate (at-least-once delivery); a spike means consumer restarts, rebalances or outbox resends. Zero forever while the outbox reports resends would point at broken deduplication. |
| `orderflow_events_dead_lettered_total` | counter | `topic` | Every increment is an event no longer processed automatically and **needs a human**. |
| `orderflow_outbox_publish_failures_total` | counter | `topic` | The relay cannot reach Kafka. Events are kept, not lost, but other modules see changes late. |
| `orderflow_outbox_pending` | gauge | none | Outbox backlog. Growing while publish failures stay flat means one event is stuck and blocks the ones behind it (ADR-0003). |

### Latency

| Metric | Type | Why it matters |
|---|---|---|
| `orderflow_outbox_publish_delay_seconds` | timer + histogram, tag `topic` | Time from the business commit until Kafka acknowledged the event: the eventual-consistency delay OrderFlow controls. Rises during Kafka outages or after a crash. |
| `spring_kafka_listener_seconds` (built in) | timer + histogram, tags `name`, `result`, `exception` | Processing time per consumed record, including the use case transaction. |
| `http_server_requests_seconds` (built in) | timer + histogram, tags `method`, `uri` (template), `status`, `outcome`, `exception` | API latency and error rate per endpoint. |

Histograms (`percentiles-histogram`) let Prometheus compute p95/p99 across instances; client-side percentiles cannot be aggregated.
Kafka client metrics (consumer lag, producer errors) and JVM, HikariCP and Tomcat metrics are bound automatically by Spring Boot.

## Cardinality rules

A tag value must come from a small, fixed set. **Never** use as a tag: order, customer, event, attempt or product IDs,
Kafka record keys, amounts, or exception messages. Those belong in logs.
Allowed: topic names, exception class names, result/outcome enums, URI *templates*.

`MetricCardinality.assertNoPerEntityTags` fails the build if any meter tag value is a UUID or uses a per-entity key.
It runs after REST traffic (`ObservabilityIT`) and after every consumer scenario, including retries and the DLT (`PaymentResultsListenerIT`).

## Alerts

Rules live in [`ops/prometheus/alerts.yml`](../../ops/prometheus/alerts.yml) and are loaded by the local Prometheus.
Each links to the [runbook](runbook.md). Thresholds are starting points; tune them on real traffic.

| Alert | Fires when |
|---|---|
| `OrderFlowEventsDeadLettered` | any event moved to a DLT in the last 5 min (also catches the first one after a restart, when the counter series is new) |
| `OrderFlowOutboxStuck` | events pending and nothing published for 5 min |
| `OrderFlowEventDelayHigh` | p99 outbox publish delay > 30 s for 10 min |
| `OrderFlowPaymentFailureRatioHigh` | > 20 % of applied payment results are failures for 15 min |
| `OrderFlowApiErrors` | > 1 % of API requests return 5xx for 5 min |

## Local monitoring stack

```bash
docker compose --profile observability up -d   # Postgres, Kafka, Prometheus (:9090), Grafana (:3000)
./mvnw spring-boot:run                         # Prometheus scrapes host.docker.internal:8080
```

- Grafana: http://localhost:3000, dashboard **OrderFlow → OrderFlow: orders and payments** (anonymous view;
  `admin`/`admin` to edit). Rows: business, API, event processing, outbox.
- Prometheus: http://localhost:9090 (targets, alerts).
- Config: `ops/prometheus/`, `ops/grafana/provisioning/`, dashboard JSON in `ops/grafana/dashboards/`.
  Edit the dashboard in Grafana, export it as JSON and replace the file; provisioned dashboards cannot be saved in place.

## Logging

| Profile | Format |
|---|---|
| `local` | Text; each line shows `[<correlationId> <eventId>]` after the thread name |
| `prod` | JSON, one object per line (Logstash format, `logging.structured.format.console`); `correlationId`, `eventId`, `eventType` are top-level fields |

**Correlation ID:** accepted from `X-Correlation-Id` (only `[A-Za-z0-9_-]{1,64}`, otherwise replaced, so callers
cannot forge log lines) or generated, and returned in the response. It is stored with each outbox event, sent as
the `correlationId` Kafka header and restored for every consumed record (`LoggingContextRecordInterceptor`), so one
ID connects the request, the database change, the event and its consumers. Records without one use their `eventId`.

**Failures:** Spring Kafka does not log failed or dead-lettered records itself; the error handler logs one `WARN`
per failed attempt and one `ERROR` when a record moves to the DLT, with topic, partition, offset and the cause.

**Never logged:** event payloads, payment failure reasons (free text from the provider), card or account data,
and parser error messages, which quote input values (only the exception type is logged for those).
`LoggingIT` sends a card number through the success, business-error and unreadable-payload paths and fails if it
appears in any log line. The **DLT keeps the original payload** and the exception in its headers: restrict access
to `*.DLT` topics like the source topics.
