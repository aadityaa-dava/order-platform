# ADR-0003: At-least-once delivery with idempotent consumers

- Status: Accepted
- Date: 2026-11-01

## Context

Messages can be delivered more than once: the outbox publisher can re-send after
a crash (ADR-0002), producers retry on timeouts, and consumers can reprocess a
record after a rebalance or crash before the offset is committed. Messages can
also be lost if we acknowledge too early. We must choose what guarantee the
system provides and where duplicates are handled.

Delivery options:
- At-most-once: never duplicates, but can lose events and stall a saga.
- At-least-once: never loses events, but can duplicate them.
- Exactly-once: Kafka transactions provide it only for read-process-write
  within Kafka. Our consumers write to PostgreSQL, and payment-service calls an
  external gateway; neither participates in a Kafka transaction.

## Decision

Guarantee at-least-once delivery end to end and make every consumer idempotent,
so the observable effect is exactly-once.

Producer side:
- `acks=all`, `enable.idempotence=true`, bounded retries with delivery timeout.
- Events are produced via the outbox (ADR-0002).
- Every event has a unique `eventId` (UUID) and the record key is the order id.

Consumer side:
- Manual offset commit after the business transaction commits
  (`enable.auto.commit=false`, ack mode RECORD); never commit before processing.
- Each consumer has a `processed_events` table with a UNIQUE constraint on
  `event_id`.
- In one DB transaction: insert the `event_id`, apply the business change, and
  insert any outgoing outbox rows. A unique-violation means the event was
  already handled; the consumer skips it and still acknowledges the record.
- Because the marker and the effect commit atomically, a crash either leaves
  both or neither, and concurrent duplicate deliveries are serialized by the
  unique constraint.

External calls (payment gateway):
- Not covered by the DB transaction, so the call is made with an idempotency key
  derived from the order id. The gateway (simulated) returns the original result
  for a repeated key. Details in the payment-service phase.

Failures that cannot be processed (poison messages) are retried with backoff and
then routed to a dead-letter topic (Phase 7), never dropped silently and never
allowed to block the partition forever.

## Consequences

Positive:
- No lost events; duplicates are harmless; correctness does not depend on broker
  or network behaving perfectly.
- Simple, testable rule: "a consumer is correct if processing the same event
  twice equals processing it once." Testable with duplicate-delivery tests.

Negative:
- Every consumer needs a processed-events table and the extra insert per
  message (small write cost, measured in Phase 10).
- The table grows; needs a retention job (older than the topic retention).
- Ordering is only guaranteed per order id (same partition); consumers must
  tolerate events for different orders interleaving.
- Idempotent handling of a duplicate must also be safe when state has moved on
  (for example a late duplicate arriving after the order is already CANCELLED).

Related but separate: the REST API (POST /orders) gets its own client-supplied
`Idempotency-Key` handling in the order-service phase, so a retried HTTP request
does not create two orders.

## Alternatives considered

- Kafka exactly-once semantics (transactional producer, read_committed): does
  not cover the database or the external gateway, so duplicates in Postgres
  would still be possible.
- Deduplicate in Redis only: fast, but the dedup marker and the business write
  are then in two systems and can disagree after a crash. Keeping the marker in
  the same database transaction avoids that.
- At-most-once with manual reconciliation: loses events and pushes complexity
  onto operators.
