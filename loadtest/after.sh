#!/usr/bin/env bash
# The "after" measurements. Settings are applied through environment variables, so the repository's
# configuration only changes once the results justify it.
cd "$(dirname "$0")/.."

echo "##### FIX 1: 12 partitions and 12 consumer threads per listener"
for topic in order.events inventory.events payment.events; do
  for name in "$topic" "$topic.DLT"; do # a dead-letter topic needs at least as many partitions as its source
    docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --alter --topic "$name" --partitions 12 > /dev/null 2>&1
  done
done
SPRING_KAFKA_LISTENER_CONCURRENCY=12 ./scripts/start-services.sh
./loadtest/sweep.sh after-partitions 10 20 40 60 80 100 120

echo
echo "##### FIX 2: outbox poll interval 200 ms -> 50 ms, on top of fix 1"
SPRING_KAFKA_LISTENER_CONCURRENCY=12 OUTBOX_POLL_INTERVAL_MS=50 ./scripts/start-services.sh
./loadtest/sweep.sh after-poll 10 20 60 100

echo
echo "##### SUMMARY"
for prefix in before after-partitions after-poll; do python3 -I loadtest/table.py "$prefix"; echo; done
