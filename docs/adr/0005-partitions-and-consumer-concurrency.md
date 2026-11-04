# ADR-0005: 12 partitions and 12 consumer threads per listener

Status: accepted

## Context
The first load test (see docs/benchmarks.md) showed a hard capacity of about 22 orders/s. Beyond that the saga p95 grew from 1.4 s at 20/s to 32.8 s at 40/s, because work queued in front of the payment consumer. Each payment takes about 126 ms in the (simulated) gateway, and the consumer ran 3 threads. By Little's law, 3 threads / 0.126 s is about 24 payments/s, which matched the measured ceiling. CPU was nearly idle: the threads were waiting on I/O, not computing.

## Decision
- 12 partitions on every topic, and on every dead-letter topic (a DLT needs at least as many partitions as its source because the recoverer publishes to the same partition number).
- `spring.kafka.listener.concurrency: 12` in every service.
- Outbox poll interval 200 ms -> 50 ms (this lowers latency; it does not change capacity).

## Consequences
- Measured capacity rose from about 22/s to about 82-85/s (about 3.8x). Below the knee (up to about 60/s) the saga p95 stays around 1 s.
- Theoretical ceiling now is 12 / 0.126 s = about 95/s. At 110/s, 11.8 of 12 payment threads were busy and payment completed 93/s: the system is again limited by consumer threads blocked in the gateway call, not by CPU.
- Ordering is preserved because the record key is the order id: all events of one order go to one partition.
- Changing the partition count of an existing topic changes which partition a key maps to. Events already in flight for an order could be reordered across the change, so the change is done on a drained system (a fresh environment is created with 12).
- More threads means more database connections in use at once. The pool is sized for this (pool pending stayed 0 in every run).
- Rejected: scaling by adding service instances on one laptop (same hardware, more memory use), and virtual threads or an async gateway call (a larger change, listed as the next step).
