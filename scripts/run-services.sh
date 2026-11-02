#!/usr/bin/env bash
# Starts order, inventory and payment services in the background. Logs: logs/<service>.log. Ctrl+C stops all.
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
export LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs   # JSON logs when started by this script; IDE runs keep readable text
PORTS="8081 8082 8083 8084"

stop() {
  for p in $PORTS; do lsof -ti :"$p" | xargs kill 2>/dev/null; done
}
trap 'echo; echo "Stopping services..."; stop; exit 0' INT TERM
stop # free the ports from any previous run

for svc in order-service inventory-service payment-service notification-service; do
  ./mvnw -q -pl services/"$svc" spring-boot:run > logs/"$svc".log 2>&1 &
done

for p in 8081 8082 8083 8084; do
  until curl -sf localhost:"$p"/actuator/health > /dev/null; do sleep 2; done
  echo "port $p is up"
done
echo "All services up. Logs are in logs/. Press Ctrl+C to stop."
wait
