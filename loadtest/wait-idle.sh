#!/usr/bin/env bash
# Waits until every Kafka consumer group has processed everything (zero lag), so a step never measures the previous one's backlog.
for _ in $(seq 1 300); do
  LAG=$(docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --all-groups 2> /dev/null \
        | awk '$6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }')
  [ "$LAG" = "0" ] && break
  sleep 1
done
