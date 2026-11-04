# ADR-0006: A saga stuck behind a dead dependency is not auto-cancelled (yet)

Status: accepted, with a known limitation

## Context
Experiments A and B (docs/failure-experiments.md) show that when payment-service is down, orders stay PENDING and their stock stays reserved, with no upper bound. After the service returns, every order completes on its own (300 of 300 in 4 s). The open question is whether to add a timeout that cancels old PENDING orders and releases the stock.

## Decision
Not now. A timeout cancel is only safe together with a refund path, because the payment may complete after the order was cancelled (a late PaymentCompleted): the customer would be charged for a cancelled order. Doing it correctly needs: an order-service sweeper, an OrderCancelled consumer in inventory (release) and payment (void or refund), and handling of the race between a late payment and the cancel. The retry layers (gateway attempts, then consumer retries up to about 15 s) also make a short timeout racy.

## Options considered
1. Cancel after N minutes plus refund flow: correct, and the largest change (three services, new states, new tests).
2. Cancel after N minutes without a refund path: unsafe, rejected.
3. Wait for recovery, alert on the age of the oldest PENDING order (chosen as the next step): matches the measured behaviour, needs no new distributed state.

## Consequences
- During an outage, reserved stock is held and orders stay PENDING for as long as it lasts. This is measured and accepted for a portfolio-scale system.
- To do next: a gauge for the age of the oldest PENDING order and an alert on it. That is not implemented yet.
- Interview answer: I found this gap by killing a service, measured it, and chose not to ship a half-correct compensation flow; here is what the full design needs.
