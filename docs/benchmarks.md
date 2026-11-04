# Benchmarks

All numbers are from one laptop (Apple M4, 16 GB RAM, Docker Desktop) running every service, the broker, the databases and the load generator (k6) together. See `loadtest/results/environment.txt`. Tracing sampling was 0 and Jaeger was stopped during load tests. The payment gateway is simulated (about 126 ms per call). Each point is one 40-second run, so treat differences of a few percent as noise.

## Method
- Open model: k6 `constant-arrival-rate` (new orders arrive at a fixed rate whether or not earlier ones finished). A closed model would slow down when the system slows down and hide the problem (coordinated omission).
- "Saga duration" is the server-side time from order creation to the final state, taken from a histogram. API latency is what the client sees for `POST /orders` (the API answers immediately; the saga continues asynchronously).
- After the load stops, the run waits until every order reaches a final state ("drain"). Capacity = orders / (load time + drain time).
- Each experiment changes one thing and the same workload is run before and after.

## Results (saga duration, ms)
| Offered rate | Before p50 / p95 | Fix 1 p50 / p95 | Fix 1+2 p50 / p95 |
|---|---|---|---|
| 10/s | 609 / 859 | 541 / 748 | 252 / 352 |
| 20/s | 709 / 1417 | 573 / 713 | 270 / 403 |
| 30/s | 6073 / 14906 | - | - |
| 40/s | 14552 / 32794 | 540 / 842 | - |
| 60/s | - | 602 / 1003 | 336 / 682 |
| 80/s | - | 850 / 1643 | - |
| 100/s | - | 2458 / 6189 | 2238 / 5601 |
| 120/s | - | 6564 / 12857 | - |

Zero failed orders and zero dead letters in every run. `POST /orders` p95 stayed between 5 and 12 ms at every rate.

## Findings
1. **Bottleneck 1: consumer threads (fix 1).** Capacity about 22/s -> about 82-85/s (3.8x). At 40/s the saga p95 went from 32.8 s to 0.84 s. Adding partitions alone changed nothing (one run on the new topics with the old thread count gave the old result); the limit was threads per listener.
2. **Bottleneck 2: outbox poll interval (fix 2).** Low-load p50 541 -> 252 ms (about 2x). It does not change capacity.
3. **Remaining limit.** At 110/s: 11.77 of 12 payment threads busy in the gateway call, 93 payments/s, gateway call 126 ms, CPU nearly idle. Throughput = threads / call time = 12 / 0.126 = about 95/s. Raw run output: `loadtest/results/fix2-run.log`.

## Next levers (not applied)
More partitions and threads (cheap, until the database pool or gateway rate limits bite), virtual threads for the blocking call, or an async gateway client with a callback event.

## Limitations
Single machine, load generator competes with the system, simulated gateway with no rate limit, one run per point, no network latency between components.

## Re-verification under the final settings
After the failure experiments the services ran with 768 MB heaps and Docker Desktop was limited to 8 GB (earlier runs: unlimited heaps, 16 GB). The outbox poll default of 50 ms was also moved into the code (during the experiment it was an environment variable). Same workload, after a 30 s warm-up:

| Offered | Saga p50 / p95 / p99 (ms) | Drain |
|---|---|---|
| 20/s | 270 / 412 / 505 | 2 s |
| 100/s | 2054 / 6223 / 7050 | 7 s |

These match the earlier numbers (270 / 403 at 20/s; 2238 / 5601 at 100/s), so the lower memory limits changed nothing measurable. A first attempt directly after starting the services (no warm-up) sent only 314 of 600 orders with a 12.8 s outbox lag and was discarded: always warm up.
