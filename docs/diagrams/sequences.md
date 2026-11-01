# Sequence diagrams

Kafka is shown as one participant; topics are order.events, inventory.events,
payment.events. "TX" means one local database transaction that includes the
outbox insert (ADR-0002). Every consumer step first dedupes on eventId
(ADR-0003).

## 1. Happy path

```mermaid
sequenceDiagram
  autonumber
  actor C as Client
  participant O as order-service
  participant K as Kafka
  participant I as inventory-service
  participant P as payment-service
  participant N as notification-service

  C->>O: POST /orders (Idempotency-Key)
  O->>O: TX: insert order PENDING + outbox(OrderCreated)
  O-->>C: 202 Accepted (orderId, PENDING)
  O->>K: OrderCreated (outbox publisher)
  K->>I: OrderCreated
  I->>I: TX: dedupe, reserve stock, outbox(StockReserved)
  I->>K: StockReserved
  K->>P: StockReserved
  P->>P: call gateway with idempotency key
  P->>P: TX: dedupe, save payment, outbox(PaymentCompleted)
  P->>K: PaymentCompleted
  K->>O: PaymentCompleted
  O->>O: TX: order CONFIRMED, outbox(OrderConfirmed)
  O->>K: OrderConfirmed
  K->>N: OrderConfirmed
  N->>N: send notification
  C->>O: GET /orders/{id}
  O-->>C: 200 CONFIRMED
```

## 2. Failure: out of stock (no compensation needed)

Nothing was reserved or charged yet, so the saga just ends in REJECTED.

```mermaid
sequenceDiagram
  autonumber
  participant O as order-service
  participant K as Kafka
  participant I as inventory-service
  participant N as notification-service

  O->>K: OrderCreated
  K->>I: OrderCreated
  I->>I: TX: dedupe, stock insufficient, outbox(StockReservationFailed)
  I->>K: StockReservationFailed
  K->>O: StockReservationFailed
  O->>O: TX: order REJECTED, outbox(OrderRejected)
  O->>K: OrderRejected
  K->>N: OrderRejected
```

## 3. Failure: payment fails (compensating action)

Stock was already reserved, so it must be released before the order is
cancelled. Compensation is a new event, never a rollback.

```mermaid
sequenceDiagram
  autonumber
  participant O as order-service
  participant K as Kafka
  participant I as inventory-service
  participant P as payment-service
  participant N as notification-service

  O->>K: OrderCreated
  K->>I: OrderCreated
  I->>K: StockReserved
  K->>P: StockReserved
  P->>P: gateway declines or times out
  P->>P: TX: dedupe, save FAILED, outbox(PaymentFailed)
  P->>K: PaymentFailed
  par order-service reacts
    K->>O: PaymentFailed
    O->>O: TX: order CANCELLED, outbox(OrderCancelled)
    O->>K: OrderCancelled
    K->>N: OrderCancelled
  and inventory-service compensates
    K->>I: PaymentFailed
    I->>I: TX: release reservation, outbox(StockReleased)
    I->>K: StockReleased
  end
```

## Notes for later phases

- Duplicate delivery of any message is a no-op (processed_events check).
- A message that keeps failing is retried with backoff, then sent to
  `<topic>.DLT` (Phase 7).
- In diagram 3, order-service and inventory-service act independently on
  PaymentFailed (choreography). The order can be CANCELLED slightly before the
  stock is released; this is eventual consistency and is acceptable because
  the order is already terminal.
