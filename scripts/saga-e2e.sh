#!/usr/bin/env bash
# End-to-end saga check: mixed workload, then assert every order reaches the expected terminal state.
# Needs order/inventory/payment services running and a healthy gateway. Exit code 0 = PASS.
set -u
BASE=${BASE:-http://localhost:8081}
HOT_N=${HOT_N:-50}
RED_N=${RED_N:-10}
SOLD_N=${SOLD_N:-5}

if [ "${1:-}" = "--worker" ]; then
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/orders" \
    -H "Content-Type: application/json" -H "Idempotency-Key: e2e-$RUN-$2-$3" \
    -d "{\"customerId\":\"e2e-$RUN\",\"customerEmail\":\"e2e@example.com\",\"currency\":\"USD\",\"items\":[{\"sku\":\"$2\",\"quantity\":1,\"unitPriceMinor\":500}]}"
  exit 0
fi

RUN=$(date +%s)
export BASE RUN
orders_db() { docker exec order-db psql -U orders -d orders -At -c "$1"; }
inv_db() { docker exec inventory-db psql -U inventory -d inventory -At -c "$1"; }
min() { [ "$1" -lt "$2" ] && echo "$1" || echo "$2"; }

AVAIL_HOT=$(inv_db "select available from stock where sku = 'SKU-HOT-ITEM'")
ONHAND_HOT=$(inv_db "select available + reserved from stock where sku = 'SKU-HOT-ITEM'")
EXP_HOT_CONFIRMED=$(min "$HOT_N" "$AVAIL_HOT")
EXP_CONFIRMED=$((EXP_HOT_CONFIRMED + RED_N))
EXP_REJECTED=$((HOT_N - EXP_HOT_CONFIRMED + SOLD_N))

echo "Workload: $HOT_N x SKU-HOT-ITEM (stock $AVAIL_HOT), $RED_N x SKU-RED-MUG, $SOLD_N x SKU-SOLD-OUT"
{
  for i in $(seq 1 "$HOT_N");  do echo "SKU-HOT-ITEM $i";  done
  for i in $(seq 1 "$RED_N");  do echo "SKU-RED-MUG $i";   done
  for i in $(seq 1 "$SOLD_N"); do echo "SKU-SOLD-OUT $i";  done
} | xargs -P 20 -n 2 "$0" --worker "$@" | sort | uniq -c | sed 's/^/  HTTP status counts: /'

echo "Waiting for the saga to finish (up to 90s)..."
for _ in $(seq 1 45); do
  PENDING=$(orders_db "select count(*) from orders where customer_id = 'e2e-$RUN' and status = 'PENDING'")
  [ "$PENDING" = "0" ] && break
  sleep 2
done

CONFIRMED=$(orders_db "select count(*) from orders where customer_id = 'e2e-$RUN' and status = 'CONFIRMED'")
REJECTED=$(orders_db "select count(*) from orders where customer_id = 'e2e-$RUN' and status = 'REJECTED'")
CANCELLED=$(orders_db "select count(*) from orders where customer_id = 'e2e-$RUN' and status = 'CANCELLED'")
ONHAND_NOW=$(inv_db "select available + reserved from stock where sku = 'SKU-HOT-ITEM'")
NEG=$(inv_db "select count(*) from stock where available < 0 or reserved < 0")

echo
echo "Result:   PENDING=$PENDING  CONFIRMED=$CONFIRMED  REJECTED=$REJECTED  CANCELLED=$CANCELLED"
echo "Expected: PENDING=0  CONFIRMED=$EXP_CONFIRMED  REJECTED=$EXP_REJECTED  CANCELLED=0"

FAIL=0
[ "$PENDING" = "0" ] || { echo "FAIL: orders still PENDING"; FAIL=1; }
[ "$CONFIRMED" = "$EXP_CONFIRMED" ] || { echo "FAIL: confirmed count"; FAIL=1; }
[ "$REJECTED" = "$EXP_REJECTED" ] || { echo "FAIL: rejected count"; FAIL=1; }
[ "$CANCELLED" = "0" ] || { echo "FAIL: unexpected cancellations"; FAIL=1; }
[ "$NEG" = "0" ] || { echo "FAIL: negative stock"; FAIL=1; }
[ "$ONHAND_NOW" = "$ONHAND_HOT" ] || { echo "FAIL: SKU-HOT-ITEM units were created or lost ($ONHAND_HOT -> $ONHAND_NOW)"; FAIL=1; }
[ "$FAIL" = "0" ] && echo "PASS: every order reached the expected terminal state; no oversell." || echo "FAILED"
exit $FAIL
