# ADR-0003: Transactional outbox and Kafka for all cross-module events

- **Status:** Accepted
- **Date:** 2026-10-30
- **Supersedes:** ADR-0002 §5 (in-process event delivery). The rest of ADR-0002 stands.

## Context

ADR-0002 delivered integration events in-process after commit, which loses events if the application crashes
between commit and delivery. Payment results must never be lost. Kafka is part of the agreed stack.
A state change and the event announcing it must be atomic, without a distributed transaction.

## Decision

1. **All cross-module integration events go through a transactional outbox and Kafka.**
2. **Write path.** The business update and an `outbox_events` row are written in **one database transaction**.
   `OutboxWriter.append` requires an existing transaction (`Propagation.MANDATORY`), so "same transaction" is enforced by code.
3. **Relay.**
   - Asynchronous and scheduled.
   - Reads unpublished rows in insertion order (`seq`; timestamps can tie) with `FOR UPDATE SKIP LOCKED`
     (safe with several instances).
   - Sends each with `acks=all` and an idempotent producer, and waits for the broker acknowledgement.
   - Only then sets `published_at`.
   - On failure: records `attempts` and `last_error`, and stops the batch (keeps per-key order). The next run retries.
4. **Delivery guarantee: at-least-once.** A crash after the broker acknowledgement but before `published_at` is
   committed re-sends the event. Every message carries a stable `eventId` header (the outbox row ID).
5. **Consumers are idempotent.**
   - They record `(eventId, consumer)` in `messaging.processed_events` (`INSERT … ON CONFLICT DO NOTHING`) in the
     **same transaction** as their business update, and skip events already recorded.
   - The transaction is the **use case's** transaction (dedup goes through a `port.out`); the listener only translates
     the record and holds no transaction.
   - Domain transitions are idempotent as well (ADR-0002).
   - The Kafka offset is committed per record, only after that transaction committed (`ack-mode: record`).
   - **Failures:** the transaction rolls back, including the dedup row, and the record is retried (3 attempts, 1 s
     apart). After the last attempt it goes to `<topic>.DLT`. Business-rule violations, unreadable JSON and missing
     headers skip the retries.
6. **Kafka record key** = aggregate ID (e.g. the order ID), so events for one aggregate keep their order within a partition.
7. **Module placement.** New technical module `com.orderflow.platform.messaging` with its own `messaging` schema.
   - Business modules use only `platform.messaging.api`, and only from their adapters, behind their own `port.out` / `adapter.in`.
   - The platform module depends on no business module.
   - Enforced by ArchUnit (architecture.md rules 7 and 12).

## Behaviour under failure

| Scenario | Behaviour |
|---|---|
| Crash before commit | Neither the state change nor the event exists: consistent. |
| Crash after commit, before publishing | The outbox row survives; the relay publishes it after restart. Nothing is lost. |
| Kafka unavailable | The send fails or times out; the row stays pending with `attempts` and `last_error`; the next runs retry. Business operations keep working; events are delayed, not lost. `orderflow.outbox.pending` shows the backlog. |
| Same event delivered twice | The consumer finds the `eventId` in `processed_events` and skips it; idempotent domain transitions are the second line of defence. |

## Alternatives considered

| Option | Why not |
|---|---|
| Publish to Kafka inside the business transaction | Kafka and the database don't share a transaction: either a lost event (DB commits, send fails) or a phantom event (send succeeds, DB rolls back) |
| Spring Modulith event publication registry | Similar guarantees, but adds a framework and conventions; our outbox is about 200 lines and fully under test |
| Change data capture (Debezium) | Robust at scale, but adds Kafka Connect infrastructure; overkill now |
| An outbox copy in each module | Duplicated relay and Kafka code; the platform module keeps it in one place behind a small API |

## Consequences

**Positive:** no lost events; no phantom events; Kafka outages don't block business operations; idempotent consumers make duplicates harmless.

**Negative:**
- Eventual consistency, with a delay of up to one relay interval.
- Consumers must be idempotent (they are, by design).
- One permanently unsendable event blocks those behind it until fixed. It is visible through `attempts`, `last_error` and the pending gauge.
- Outbox rows accumulate; a cleanup job is an open point.

**Open points:** outbox cleanup and retention; a dead-letter strategy for poison messages; alerting on the pending gauge.
