# ADR-0001: Choreography-based saga for order processing

- Status: Accepted
- Date: 2026-11-01

## Context

Placing an order spans three services that each own their own database:
order-service, inventory-service, and payment-service. A single ACID transaction
across them is not possible without distributed transactions (2PC/XA), which
couple service availability, hold locks across network calls, and are not
supported by Kafka. We need a way to reach a consistent outcome across services
and to undo earlier steps when a later step fails.

The saga pattern solves this: a sequence of local transactions, each followed by
an event, with compensating actions for failure. There are two coordination
styles:

- Orchestration: a central coordinator sends commands and tracks state.
- Choreography: services react to each other's events; no coordinator.

## Decision

Use choreography over Kafka.

Happy path:
1. order-service creates the order (PENDING) and emits `OrderCreated`.
2. inventory-service reserves stock and emits `StockReserved`.
3. payment-service charges the customer and emits `PaymentCompleted`.
4. order-service marks the order CONFIRMED and emits `OrderConfirmed`.
5. notification-service reacts to terminal order events.

Failure paths (compensation):
- Stock cannot be reserved: inventory emits `StockReservationFailed`;
  order-service marks the order REJECTED.
- Payment fails: payment emits `PaymentFailed`; inventory releases the
  reservation and emits `StockReleased`; order-service marks the order CANCELLED.

Order-service owns the order state machine
(PENDING -> CONFIRMED | REJECTED | CANCELLED). Each service is the only writer
of its own data and communicates only through events.

## Consequences

Positive:
- No single point of failure or central bottleneck; services stay loosely coupled.
- Adding a consumer (e.g. notification-service) requires no change to producers.
- Fits a short, linear flow with few participants.

Negative:
- The end-to-end flow is not written in one place; it is implied by who
  subscribes to what. Mitigations: documented state machine and sequence
  diagrams in the design doc, and distributed tracing across Kafka (Phase 9).
- Risk of cyclic event dependencies as the flow grows. Mitigation: event
  ownership is explicit (each event has exactly one producing service).
- Harder to answer "where is order X stuck?". Mitigation: correlation/order ids
  on every event and log line.
- Every step must be idempotent, because Kafka delivers at least once.

## Alternatives considered

- Orchestration (central saga coordinator): clearer flow and easier
  monitoring of long sagas, but adds a new service that must be highly
  available, and centralizes business logic. A better fit if the flow grows to
  many steps, branches, or needs timeouts per step. Revisit if that happens.
- Two-phase commit / XA: strong consistency, but blocks on coordinator
  failure, ties availability of all services together, and is not supported by
  Kafka or most cloud-native stacks.
