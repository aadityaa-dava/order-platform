#!/usr/bin/env bash
# Runs 40 orders/s for 40s and samples the system mid-overload. Usage: loadtest/diagnose.sh [rate]
set -u
cd "$(dirname "$0")/.."
RATE=${1:-40}
./scripts/restock.sh > /dev/null
RUN=$(date +%s)
k6 run --quiet -e RATE="$RATE" -e DURATION=40s -e RUN="$RUN" loadtest/scripts/orders.js > /dev/null 2>&1 &
K6=$!
sleep 25

q() { curl -s --get localhost:9090/api/v1/query --data-urlencode "query=$1" \
      | python3 -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(round(float(r[0]['value'][1]), 2) if r else 'n/a')"; }

echo "=== at t=25s of a ${RATE}/s load"
echo "--- consumer group lag from the broker (records not yet processed), by group and topic"
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2>/dev/null \
  | awk '$6 ~ /^[0-9]+$/ { lag[$1 "  " $2] += $6 } END { for (k in lag) printf "  %-48s %6d\n", k, lag[k] }' | sort
echo "--- what the Kafka client metric says (records-lag-max, max over all consumers)"
echo "  $(q 'max(kafka_consumer_fetch_manager_records_lag_max)')"
echo "--- payment-service: threads busy inside the gateway call (average over the last 20s, out of the consumer threads)"
echo "  $(q 'sum(rate(payment_gateway_charge_seconds_sum[20s]))')"
echo "--- payment-service: payments completed per second"
echo "  $(q 'sum(rate(payment_gateway_charge_seconds_count[20s]))')"
echo "--- average gateway call time (ms)"
echo "  $(q '1000 * sum(rate(payment_gateway_charge_seconds_sum[20s])) / sum(rate(payment_gateway_charge_seconds_count[20s]))')"
echo "--- CPU used by each service (fraction of one core)"
for app in order inventory payment notification; do echo "  $app-service: $(q "process_cpu_usage{application=\"$app-service\"}")"; done
wait $K6
