# Testing

## How to run

- `./mvnw test`: unit tests only. Fast, no Docker needed.
- `./mvnw verify`: unit and integration tests, then the coverage gate. Needs Docker, because
  integration tests start real PostgreSQL and Kafka containers (Testcontainers, free).
- `python3 scripts/coverage-summary.py` after `verify`: coverage per module and per area.

Unit tests are named `*Test`; integration tests are named `*IT` and run in the Failsafe phase.

## What each layer proves

| Layer | Examples | Why it is not a mock |
|---|---|---|
| Unit | order state machine, money formatting, gateway retry and timeout logic, schema rejects bad events | pure logic, milliseconds |
| Integration (real PostgreSQL) | 50 concurrent orders for 10 units never oversell; opposing lock orders do not deadlock; one terminal order state when two events race; one payment and one email per order under duplicate and concurrent delivery; order and outbox event commit or roll back together | locking, `ON CONFLICT`, `SKIP LOCKED` and transactions only behave like this on a real database |
| Integration (real Kafka) | outbox publisher delivers with key and headers, keeps per-order ordering, leaves events unpublished when the broker is unreachable | real producer and broker behaviour |
| End to end, per consumer | an event on a topic changes the database; a poison message is dead-lettered and does not block the message behind it; an unknown order is retried then dead-lettered | exercises the real listeners and the shared error handler |
| Contract | every event a service writes validates against a shared JSON Schema; every consumer reads the shared examples and tolerates added fields | catches a renamed field before runtime |

## Coverage gate

`./mvnw verify` fails if line coverage of core logic falls below 80% in any module. Core logic
means business rules, external clients, data access, the Kafka listeners and the shared
libraries. Configuration classes, DTO records and `main()` methods are not counted.

## Test design rules

- Integration tests share one container per JVM (singleton pattern). Spring caches the
  application context across classes, so a container per class breaks it (see what-broke.md #4).
- Test events are built from the contract examples and validated before sending, so a test cannot
  pass by sending a malformed event.
- Tests that start Kafka listeners close their context afterwards so consumers cannot leak into
  other test classes.

## Known gaps

- The HTTP layer is covered through MockMvc, not over a real socket.
- Failure scenarios that need a process or container to die (kill a service mid-saga, restart the
  broker) are experiments in Phase 11, not automated tests.
