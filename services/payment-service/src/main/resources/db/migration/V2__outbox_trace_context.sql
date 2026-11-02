-- W3C traceparent of the span that wrote the event, so the publisher can continue the same trace later.
ALTER TABLE outbox ADD COLUMN trace_context VARCHAR(255);
