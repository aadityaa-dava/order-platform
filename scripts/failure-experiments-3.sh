#!/usr/bin/env bash
cd ~/dev/order-platform
q()   { docker exec order-db psql -U orders -d orders -At -c "$1"; }
inv() { docker exec inventory-db psql -U inventory -d inventory -At -c "$1"; }
DEMO="'SKU-RED-MUG','SKU-BLUE-MUG','SKU-TSHIRT-M'"
step() { local T0; T0=$(q "select now()")
  ./loadtest/run-step.sh "$1" "$2" "$3" | grep -E "^\| $1|WARNING"
  echo "  orders by status: $(q "select status || '=' || count(*) from orders where created_at >= '$T0' group by status order by status" | tr '\n' ' ')"
  echo "  reserved on demo SKUs: $(inv "select coalesce(sum(reserved),0) from stock where sku in ($DEMO)")"; }
curl -sf localhost:8083/actuator/health > /dev/null || ./scripts/start-services.sh
echo "gateway: $(curl -s localhost:8083/admin/gateway)"
echo "##### CONTROL: healthy system under today's settings (8 GB Docker, 768 MB heaps, 12 threads, poll 50 ms)"
step control-20 20 30
step control-100 100 40
echo; echo "##### DOUBLE-CHARGE CHECK: one payment row per order?"
docker exec payment-db psql -U payments -d payments -c '\d payments' 2>&1 | head -n 22
docker exec payment-db psql -U payments -d payments -At -c "select count(*) as payment_rows, count(distinct order_id) as distinct_orders from payments" 2>&1
echo "##### DONE"
