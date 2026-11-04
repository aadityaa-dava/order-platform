# Failure experiments

Every experiment starts from a healthy, warmed-up system, uses the same k6 workload as the benchmarks, and checks invariants afterwards, not only latency: every order reaches a final state, stock reserved on the demo SKUs equals the number of CONFIRMED orders, nothing in a dead-letter topic. Raw output is in `loadtest/results/failure-*.log`. Experiments A, B and C1-C3 ran with the outbox poll at 200 ms (the 50 ms default was added afterwards); that adds a constant to saga latency and does not change any outcome.

| # | Failure injected | Result |
|---|---|---|
| A | `kill -9` payment-service at t=10 s of a 20/s load, restart 15 s later | 601 of 601 orders CONFIRMED, 0 stuck, 0 dead letters. While down: 187 CONFIRMED, 354 PENDING. Drained in 29 s after the restart. |
| B | payment-service down for 60 s, 300 orders sent | All 300 PENDING for the whole outage (oldest 60 s), stock held. After the restart the backlog drained in 4 s (300 x 0.126 s / 12 threads = 3.2 s expected). 300 of 300 CONFIRMED. |
| C1 | Slow gateway, 400-600 ms per call (capacity 12 / 0.5 s = about 24/s) | 20/s: saga p50 1.7 s, p95 3.4 s, drain 4 s. 40/s (above capacity): p50 11 s, p95 24 s, drain 29 s. Nothing lost; all CONFIRMED. |
| C2 | 25% of payments declined, 20/s | 155 CANCELLED (25.8%), 446 CONFIRMED. Reserved stock = 446 = CONFIRMED, so every declined order released its stock. Saga p50/p95 552/779 ms vs 577/775 ms for a healthy control at the same settings: compensation adds no visible latency. |
| C3 | 30% of gateway calls time out, 10/s | All 300 CONFIRMED, 0 dead letters, reserved = 300. Saga p50 2.5 s, p95 6.9 s, p99 12 s: each timeout holds a consumer thread for the 2 s client timeout, and orders that exhaust the 3 gateway attempts fall through to the consumer retry with the same idempotency key. |

## Double-charge check
`payments` held 78,414 rows for 78,414 distinct orders after all experiments, and the table has a unique constraint on `order_id`, so a second payment row for an order is impossible. The simulated gateway's own ledger is not observable; idempotency there relies on the `pay-<orderId>` key by design.

## What this shows
- Crashes and outages do not lose or duplicate work: events wait in Kafka, consumers are idempotent, the outbox keeps producing.
- The API stays available while a downstream service is dead (all orders were accepted in every experiment).
- Compensation (stock release) is correct under load.
- Slow dependencies cause queueing, not failure: latency rises, data stays consistent. Capacity is threads / call time.

## Known gap (ADR-0006)
Nothing bounds how long an order may stay PENDING while a dependency is down: the stock stays reserved until it recovers. After recovery everything completes by itself (experiment B), but there is no timeout and no alert on the age of the oldest PENDING order.
