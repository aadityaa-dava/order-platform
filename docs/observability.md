# Observability

Three signals, each answering a different question.

| Signal | Question it answers | Where |
|---|---|---|
| Logs | What happened to this order? | `scripts/follow-order.py <orderId or correlationId>` merges all services' JSON logs |
| Metrics | How is the system doing? Is something getting worse? | Grafana http://localhost:3000, Prometheus http://localhost:9090 |
| Traces | Where did the time go for one request, across services and Kafka? | Jaeger http://localhost:16686, `scripts/trace-summary.py` |

## Correlation

- `X-Correlation-Id` is accepted at the API (or generated), echoed in the response, stored on the
  order, and carried in every event envelope.
- Every log line written while handling an HTTP request or a Kafka record carries `correlationId`,
  `orderId`, `eventId`, `eventType`, plus the trace id.
- Services started with `scripts/run-services.sh` write JSON logs to `logs/`. Runs from an IDE keep
  readable text.

## Metrics

Business: `orders_saga_duration_seconds` (accepted to final outcome), `orders_completed_total`,
`outbox_events_written_total{eventType}`. Reliability: `outbox_unpublished`, `outbox_oldest_age_seconds`,
`outbox_publish_failures_total`, `consumer_retries_total`, `consumer_dead_letters_total`.
Dependencies: `payment_gateway_charge_seconds`. Platform: HTTP latency histograms, JVM, connection pool,
Kafka client lag.

Alerts (Prometheus, http://localhost:9090/alerts): `ServiceDown`, `OutboxLagHigh`,
`DeadLettersAppearing`, `SagaSlow`.

## Tracing

OpenTelemetry through Micrometer Tracing, exported over OTLP to Jaeger. HTTP and Kafka are
instrumented, and the trace context travels through the outbox (ADR-0004). Scheduler polling and
actuator scrapes are excluded so traces show business work only. Sampling is 100% locally; a real
deployment would sample.

## Limitations

- Jaeger uses in-memory storage; traces disappear when it restarts.
- Prometheus has alert rules but no Alertmanager, so nothing is paged.
- Grafana runs without a login; this configuration is for local use only.
- Database calls are not traced as separate spans.
