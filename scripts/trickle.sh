#!/usr/bin/env bash
# Sends a steady stream of orders so dashboards have data. Usage: ./scripts/trickle.sh [seconds] [orders per second]
set -u
DURATION=${1:-90}
RATE=${2:-3}
BASE=${BASE:-http://localhost:8081}
RUN=$(date +%s)
"$(dirname "$0")/restock.sh" > /dev/null

echo "Sending $RATE orders/second for ${DURATION}s ..."
for s in $(seq 1 "$DURATION"); do
  for i in $(seq 1 "$RATE"); do
    curl -s -o /dev/null -X POST "$BASE/orders" -H "Content-Type: application/json" -H "Idempotency-Key: trickle-$RUN-$s-$i" \
      -d "{\"customerId\":\"trickle\",\"customerEmail\":\"trickle@example.com\",\"currency\":\"USD\",\"items\":[{\"sku\":\"SKU-TSHIRT-M\",\"quantity\":1,\"unitPriceMinor\":2500}]}" &
  done
  sleep 1
done
wait
echo "Done: $((DURATION * RATE)) orders sent."
