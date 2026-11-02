# ADR-0004: Carry the trace context through the outbox

- Status: Accepted
- Date: 2026-11-02

## Context

Distributed tracing connects the work done for one request across services. Kafka propagation is
automatic: the producer injects a `traceparent` header and the consumer continues the trace.

The transactional outbox (ADR-0002) breaks this. The HTTP request writes the event to the outbox and
finishes; the event is sent later, by the publisher's scheduler thread, where no trace is active. The
Kafka producer therefore started a new trace, and at every hop of the saga. Measured result: one order
produced five disconnected traces, and the time an event spent waiting in the outbox was invisible.

## Decision

Store the W3C `traceparent` of the active span in a `trace_context` column of the outbox row when the
event is written. When the publisher sends the row, it starts an `outbox publish` span whose parent is
that stored context, and sends inside it. The Kafka send span, and every consumer span after it,
becomes part of the original trace.

The start time of the `outbox publish` span is the moment of publishing, so the gap between the
originating span and the relay span is the time the event waited in the outbox.

Tracing is optional: with no tracer configured the column stays null and the publisher behaves exactly
as before. Failure to restore a context never prevents an event from being published.

## Consequences

Positive:
- One order is one trace across all four services (21 spans in the measured run).
- Outbox delay is visible per event. During a broker outage the trace shows the wait, then the
  saga completing once Kafka returned.

Negative:
- A schema change (nullable `trace_context` column) in every service that publishes events.
- The trace shows a long span gap, not a long span, while an event waits; readers must know to look
  for it. The `outbox_oldest_age_seconds` metric and its alert cover the operational side.
- Trace propagation through the outbox is verified by an end-to-end script run, not yet by an
  automated test.

## Alternatives considered

- Accept broken traces and correlate by `correlationId`: works for logs, but loses the timeline,
  the span tree, and the outbox delay.
- Span links instead of a parent: keeps traces separate but linked; harder to read, and most
  tooling renders a single trace far better.
- Change data capture (Debezium) instead of polling: the same problem exists unless the trace
  context is stored in the row and added as a header; the decision here would still apply.
