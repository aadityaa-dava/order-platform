#!/usr/bin/env bash
# One load-test step: restock, send orders at a fixed rate, wait until every order reaches a final state,
# then collect the numbers. Usage: loadtest/run-step.sh LABEL RATE [DURATION_SECONDS]
set -u
cd "$(dirname "$0")/.."
LABEL=$1
RATE=$2
DURATION=${3:-40}
mkdir -p loadtest/results

./scripts/restock.sh > /dev/null
./loadtest/wait-idle.sh
RUN=$(date +%s)
START=$RUN

k6 run --quiet -e RATE="$RATE" -e DURATION="${DURATION}s" -e RUN="$RUN" \
  --summary-export "loadtest/results/$LABEL-k6.json" loadtest/scripts/orders.js > "loadtest/results/$LABEL-k6.txt" 2>&1
SENT_END=$(date +%s)

# the saga is asynchronous: wait until every order of this run has a final state (give up after 3 minutes)
PENDING=-1
for _ in $(seq 1 180); do
  PENDING=$(docker exec order-db psql -U orders -d orders -At -c \
    "select count(*) from orders where customer_id like 'k6-$RUN-%' and status = 'PENDING'")
  [ "$PENDING" = "0" ] && break
  sleep 1
done
END=$(date +%s)
COMPLETED=$(docker exec order-db psql -U orders -d orders -At -c \
  "select count(*) from orders where customer_id like 'k6-$RUN-%' and status <> 'PENDING'")
sleep 7 # let Prometheus scrape the final values

python3 -I loadtest/collect.py "$LABEL" "$RATE" "$START" "$END" "$((END - SENT_END))" "$PENDING" "$COMPLETED"
