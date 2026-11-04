#!/usr/bin/env bash
cd ~/dev/order-platform
q()   { docker exec order-db psql -U orders -d orders -At -c "$1"; }
inv() { docker exec inventory-db psql -U inventory -d inventory -At -c "$1"; }
status() { echo "  orders by status: $(q "select status || '=' || count(*) from orders where customer_id like 'k6-$1-%' group by status order by status" | tr '\n' ' ')"; }
stock()  { echo "  stock reserved right now (all SKUs): $(inv "select coalesce(sum(reserved),0) from stock")"; }
dlt() { for t in order.events inventory.events payment.events; do
  n=$(docker exec kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server localhost:29092 --topic $t.DLT 2> /dev/null | awk -F: '{s+=$3} END{print s+0}')
  echo "  $t.DLT messages (cumulative): $n"; done; }
drain() { local i=0 p=-1; for i in $(seq 1 240); do
  p=$(q "select count(*) from orders where customer_id like 'k6-$1-%' and status='PENDING'"); [ "$p" = 0 ] && break; sleep 1; done
  echo "  waited ${i}s for the saga to finish; still PENDING: $p"; }

./scripts/start-services.sh
echo; echo "##### EXPERIMENT A: kill -9 payment-service at t=10s of a 20/s load, restart 15s later"
./scripts/restock.sh > /dev/null; ./loadtest/wait-idle.sh
RUN=A$(date +%s)
k6 run --quiet -e RATE=20 -e DURATION=30s -e RUN=$RUN loadtest/scripts/orders.js > /tmp/k6-A.txt 2>&1 &
K6=$!
sleep 10; ./scripts/svc.sh stop payment-service 8083; echo "  payment-service killed"
sleep 15; echo "  while it is down:"; status $RUN; stock
./scripts/svc.sh start payment-service 8083; echo "  payment-service restarted"
wait $K6; echo "  load finished; sent: $(q "select count(*) from orders where customer_id like 'k6-$RUN-%'")"
drain $RUN; status $RUN; stock; dlt

echo; echo "##### EXPERIMENT B: payment-service down for 60s, 300 orders (15s at 20/s)"
./scripts/restock.sh > /dev/null; ./loadtest/wait-idle.sh
RUN=B$(date +%s)
./scripts/svc.sh stop payment-service 8083; echo "  payment-service killed (before any load)"
k6 run --quiet -e RATE=20 -e DURATION=15s -e RUN=$RUN loadtest/scripts/orders.js > /tmp/k6-B.txt 2>&1
sleep 45; echo "  60s after the outage began:"; status $RUN; stock
echo "  oldest PENDING order age (s): $(q "select coalesce(round(extract(epoch from now() - min(created_at))),-1) from orders where customer_id like 'k6-$RUN-%' and status='PENDING'")"
./scripts/svc.sh start payment-service 8083; echo "  payment-service restarted"
drain $RUN; status $RUN; stock; dlt
echo; echo "##### GatewayAdminController"; cat services/payment-service/src/main/java/dev/aadityaa/orderplatform/payment/gateway/GatewayAdminController.java
echo "##### DONE"
