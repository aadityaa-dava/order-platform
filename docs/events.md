# Event contracts

All events are JSON, published to Kafka with key = orderId (see architecture.md).

## Envelope (every event)

| Field | Type | Description |
|---|---|---|
| eventId | UUID | Unique per event; consumers dedupe on it (ADR-0003) |
| eventType | string | e.g. OrderCreated |
| eventVersion | int | Schema version of the payload, starts at 1 |
| occurredAt | ISO-8601 UTC | When the state change happened |
| orderId | UUID | Aggregate id and Kafka key |
| correlationId | UUID | Constant across the whole saga; used in logs and traces |
| producer | string | Service name |
| payload | object | Event-specific data (below) |

Money: integer minor units (`amountMinor`, e.g. cents) plus `currency` (ISO-4217).
Never floating point.

## Events

### order.events (producer: order-service)

| Event | Payload |
|---|---|
| OrderCreated | customerId, customerEmail, items[{sku, quantity, unitPriceMinor}], totalAmountMinor, currency |
| OrderConfirmed | customerId, customerEmail, totalAmountMinor, currency |
| OrderRejected | customerId, customerEmail, reason |
| OrderCancelled | customerId, customerEmail, reason |

### inventory.events (producer: inventory-service)

| Event | Payload |
|---|---|
| StockReserved | reservationId, items[{sku, quantity}], customerId, totalAmountMinor, currency |
| StockReservationFailed | reason (OUT_OF_STOCK, UNKNOWN_SKU), unavailable[{sku, requested, available}] |
| StockReleased | reservationId, reason (PAYMENT_FAILED) |

StockReserved copies customerId and the amount from OrderCreated so
payment-service can charge without calling order-service (event-carried state
transfer).

### payment.events (producer: payment-service)

| Event | Payload |
|---|---|
| PaymentCompleted | paymentId, totalAmountMinor, currency, gatewayReference |
| PaymentFailed | reason (DECLINED, GATEWAY_TIMEOUT, GATEWAY_ERROR), detail |

## Example

```json
{
  "eventId": "6f1c2d4e-8b7a-4c1e-9a55-3d2f1b7e0a11",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "occurredAt": "2026-11-01T16:30:00Z",
  "orderId": "b2a1c3d4-5e6f-4789-a0b1-c2d3e4f50617",
  "correlationId": "0c9d8e7f-6a5b-4c3d-2e1f-0a9b8c7d6e5f",
  "producer": "order-service",
  "payload": {
    "customerId": "cust-1001",
    "customerEmail": "jane@example.com",
    "items": [{ "sku": "SKU-RED-MUG", "quantity": 2, "unitPriceMinor": 1250 }],
    "totalAmountMinor": 2500,
    "currency": "USD"
  }
}
```

## Compatibility rules

- Additive changes only within a version: new optional fields are allowed.
- Consumers ignore unknown fields (tolerant reader).
- Removing or renaming a field, or changing its meaning or type, is a breaking
  change: publish a new `eventVersion` (and run both versions during migration).
- Contract tests (Phase 8) verify producers and consumers agree on these shapes.

## Serialization decision

JSON with this envelope, no schema registry. Trade-off: a registry (Avro or
JSON Schema) rejects incompatible schemas at publish time and gives compact
binary encoding, but adds another service to run and operate. Here, contract
tests in CI catch incompatibilities instead. Limitation: nothing stops a
producer from publishing a malformed event at runtime; consumers validate and
send invalid messages to the dead-letter topic (Phase 7).
