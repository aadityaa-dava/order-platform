#!/usr/bin/env bash
# Sends poison messages and shows they are parked in the dead-letter topics instead of lost.
# Needs the services running. Takes about 40 seconds.
set -u
produce() { printf '%s\n' "$2" | docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic "$1" > /dev/null; }
ghost=$(uuidgen | tr 'A-Z' 'a-z')

echo "1) malformed JSON on order.events          -> expect: dead-lettered at once, no retries"
produce order.events 'this is not json'
echo "2) event with no eventId on order.events   -> expect: dead-lettered at once"
produce order.events "{\"eventType\":\"OrderCreated\",\"orderId\":\"$ghost\",\"payload\":{}}"
echo "3) PaymentCompleted for an unknown order   -> expect: retries 1s,2s,4s,8s, then dead-lettered"
produce payment.events "{\"eventId\":\"$(uuidgen | tr 'A-Z' 'a-z')\",\"eventType\":\"PaymentCompleted\",\"eventVersion\":1,\"orderId\":\"$ghost\",\"correlationId\":\"$(uuidgen | tr 'A-Z' 'a-z')\",\"producer\":\"poison-test\",\"payload\":{\"gatewayReference\":\"gw_x\"}}"

echo "Waiting 30s for the retries to be exhausted..."
sleep 30
for topic in order.events.DLT payment.events.DLT; do
  echo; echo "=== $topic"
  docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic "$topic" \
    --from-beginning --timeout-ms 8000 --property print.headers=true 2>&1 \
    | grep -oE "kafka_dlt-(original-topic|exception-cause-fqcn):[^,]*" 
done
echo; echo "=== retry trail in the order-service log"
grep -E "Delivery attempt|Dead-lettering" logs/order-service.log | cut -c1-230 | tail -n 8
