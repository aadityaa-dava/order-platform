# ADR-0002: Transactional outbox with a polling publisher

- Status: Accepted
- Date: 2026-11-01

## Context

Each service must update its own database and publish an event describing the
change. Writing to Postgres and Kafka separately (a "dual write") cannot be made
atomic:

- DB commit succeeds, Kafka publish fails or the process crashes: the state
  change happened but no event is ever emitted, so the saga stalls.
- Kafka publish succeeds, DB commit fails: consumers act on an event for state
  that does not exist.

Kafka transactions do not include the relational database, and distributed
transactions are ruled out in ADR-0001.

## Decision

Use the transactional outbox pattern in every service that publishes events
(order, inventory, payment).

1. In the same local DB transaction as the business change, insert a row into an
   `outbox` table (event id, aggregate id, event type, payload JSON, created_at,
   published_at NULL).
2. A polling publisher in the same service reads unpublished rows in id order
   using `SELECT ... WHERE published_at IS NULL ORDER BY id LIMIT :n
   FOR UPDATE SKIP LOCKED`, sends them to Kafka, waits for the broker ack, then
   sets `published_at`.
3. The Kafka record key is the aggregate id (the order id), so all events for
   one order land on the same partition and keep their order.
4. Each event carries a unique `eventId`. Consumers use it for idempotency
   (see ADR-0003).

Published rows are deleted or archived by a periodic cleanup job so the table
does not grow without bound.

## Consequences

Positive:
- State change and event are atomic: either both exist or neither does.
- No extra infrastructure; the publisher is plain application code, easy to
  test with Testcontainers.
- Kafka outages do not fail requests; events accumulate in the outbox and drain
  when the broker returns.

Negative:
- Delivery is at-least-once: if the service crashes after the Kafka ack but
  before marking the row published, the event is sent again. Consumers must be
  idempotent.
- Added latency: events are published after the next poll (target well under
  one second; measured in Phase 10).
- Polling puts steady read load on the database. Mitigations: partial index on
  unpublished rows, tuned poll interval and batch size.
- Strict per-aggregate ordering holds only while events for one aggregate are
  published by one poller at a time. With multiple replicas, `SKIP LOCKED` can
  let a later batch overtake an earlier locked one. Accepted for now (one
  publisher instance per service); revisit by partitioning pollers by aggregate
  hash if we scale out.

## Alternatives considered

- Change data capture (Debezium reading the Postgres WAL): lower latency, no
  polling load, preserves commit order. Needs Kafka Connect and Debezium
  deployed and operated. A good production upgrade; too much infrastructure for
  this project's first iteration.
- Publish to Kafka first, then write the DB: just flips which failure mode you
  get; does not solve atomicity.
- Kafka transactions plus DB in a best-effort order: Kafka transactions only
  cover Kafka, so this does not remove the dual write.
- Do nothing and rely on retries: leaves a window where state and events
  diverge permanently.
