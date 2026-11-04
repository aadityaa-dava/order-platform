#!/usr/bin/env bash
cd ~/dev/order-platform
q()   { docker exec order-db psql -U orders -d orders -At -c "$1"; }
inv() { docker exec inventory-db psql -U inventory -d inventory -At -c "$1"; }
GW=localhost:8083/admin/gateway
DEMO="'SKU-RED-MUG','SKU-BLUE-MUG','SKU-TSHIRT-M'"
gw() { curl -s -X PUT -H 'Content-Type: application/json' -d "$1" $GW > /dev/null; echo "  gateway now: $(curl -s $GW)"; }
dlt() { for t in order.events inventory.events payment.events; do
  n=$(docker exec kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server localhost:29092 --topic $t.DLT 2> /dev/null | awk -F: '{s+=$3} END{print s+0}')
  echo "  $t.DLT messages (cumulative): $n"; done; }
step() { # label rate duration
  local T0; T0=$(q "select now()")
  ./loadtest/run-step.sh "$1" "$2" "$3" | grep -E "^\| $1|WARNING"
  echo "  orders by status: $(q "select status || '=' || count(*) from orders where created_at >= '$T0' group by status order by status" | tr '\n' ' ')"
  echo "  reserved on demo SKUs: $(inv "select coalesce(sum(reserved),0) from stock where sku in ($DEMO)")   (must equal the CONFIRMED count)"
  dlt
}
curl -sf localhost:8083/actuator/health > /dev/null || ./scripts/start-services.sh
DEFAULT=$(curl -s $GW); echo "gateway defaults: $DEFAULT"
echo "reserved on NON-demo SKUs (the constant offset seen earlier): $(inv "select coalesce(sum(reserved),0) from stock where sku not in ($DEMO)")"
echo "(table columns: label | rate | sent | completed | API p95 | saga p50 | p95 | p99 | drain s | outbox lag | consumer lag | pool pending | dead letters)"

echo; echo "##### C1: slow gateway, 400-600 ms per call instead of about 126 ms (capacity = 12 threads / 0.5 s = about 24/s)"
gw '{"minLatencyMs":400,"maxLatencyMs":600}'
step slow-gw-20 20 30
step slow-gw-40 40 30
gw "$DEFAULT"

echo; echo "##### C2: 25% of payments declined (does the compensation release the stock under load?)"
gw '{"declineRate":0.25}'
step decline-25 20 30
gw "$DEFAULT"

echo; echo "##### C3: 30% of gateway calls time out (outcome unknown, retried with the same idempotency key)"
gw '{"timeoutRate":0.3}'
step timeout-30 10 30
gw "$DEFAULT"

echo; echo "##### payment database tables (for the double-charge check)"
for u in payments payment; do docker exec payment-db psql -U $u -d $u -c '\dt' 2> /dev/null && break; done
echo "##### DONE"
