#!/usr/bin/env bash
# Fires N concurrent single-unit orders at one SKU, then checks inventory never oversold.
# Needs order-service (8081) and inventory-service (8082) running. SKU-HOT-ITEM starts with 10 units.
set -u
BASE=${BASE:-http://localhost:8081}
SKU=${SKU:-SKU-HOT-ITEM}
N=${N:-50}

if [ "${1:-}" = "--worker" ]; then
  i=$2
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/orders" \
    -H "Content-Type: application/json" -H "Idempotency-Key: conc-$RUN-$i" \
    -d "{\"customerId\":\"c$i\",\"customerEmail\":\"c$i@example.com\",\"currency\":\"USD\",\"items\":[{\"sku\":\"$SKU\",\"quantity\":1,\"unitPriceMinor\":500}]}"
  exit 0
fi

RUN=$(date +%s)
export BASE SKU RUN
echo "Sending $N concurrent orders for $SKU ..."
seq 1 "$N" | xargs -P 20 -n 1 "$0" --worker | sort | uniq -c | sed 's/^/  HTTP status counts: /'
echo "Waiting for the saga to process..."
sleep 10
docker exec inventory-db psql -U inventory -d inventory \
  -c "select sku, available, reserved, available + reserved as on_hand from stock where sku = '$SKU';" \
  -c "select r.status, count(*) from reservations r join reservation_items i on i.reservation_id = r.id where i.sku = '$SKU' group by r.status;" \
  -c "select event_type, count(*) from outbox group by event_type order by event_type;"
