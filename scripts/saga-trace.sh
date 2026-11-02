#!/usr/bin/env bash
# Creates one order and prints its event trail from each service's outbox.
# Usage: ./scripts/saga-trace.sh [SKU] [QTY]
set -u
BASE=${BASE:-http://localhost:8081}
SKU=${1:-SKU-RED-MUG}
QTY=${2:-1}
KEY="trace-$(date +%s)-$RANDOM"

RESP=$(curl -s -X POST "$BASE/orders" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
  -d "{\"customerId\":\"cust-trace\",\"customerEmail\":\"trace@example.com\",\"currency\":\"USD\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":$QTY,\"unitPriceMinor\":1250}]}")
ID=$(echo "$RESP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
if [ -z "$ID" ]; then echo "Order creation failed: $RESP"; exit 1; fi
echo "order: $ID  ($QTY x $SKU)"
sleep "${WAIT:-6}"

q() { docker exec "$1" psql -U "$2" -d "$3" -At -F '  |  ' -c "$4"; }
echo "--- order-service outbox      (event | published)"
q order-db orders orders "select event_type, published_at is not null from outbox where aggregate_id = '$ID' order by id"
echo "--- inventory-service outbox"
q inventory-db inventory inventory "select event_type, published_at is not null from outbox where aggregate_id = '$ID' order by id"
echo "--- payment-service outbox"
q payment-db payments payments "select event_type, published_at is not null from outbox where aggregate_id = '$ID' order by id"
echo "--- payment row               (status | failure | attempts)"
q payment-db payments payments "select status, coalesce(failure_reason, '-'), attempts from payments where order_id = '$ID'"
echo "--- stock                     (sku | available | reserved)"
q inventory-db inventory inventory "select sku, available, reserved from stock where sku = '$SKU'"
echo "--- order status via API"
curl -s "$BASE/orders/$ID" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p'
echo "--- notification-service      (type | recipient)"
q notification-db notifications notifications "select type, recipient from notifications where order_id = '$ID'"
