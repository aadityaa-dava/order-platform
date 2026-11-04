#!/usr/bin/env bash
# Starts the four services in the background and returns once they are healthy. Logs: logs/<service>.log
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
export LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
# Load tests must not trace everything (it costs CPU and memory). Use SAMPLING=1.0 for demos.
export MANAGEMENT_TRACING_SAMPLING_PROBABILITY=${SAMPLING:-0.0}
./scripts/stop-services.sh
for svc in order-service inventory-service payment-service notification-service; do
  nohup ./mvnw -q -pl services/"$svc" spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m -Xms128m" > logs/"$svc".log 2>&1 &
done
for p in 8081 8082 8083 8084; do until curl -sf localhost:"$p"/actuator/health > /dev/null; do sleep 2; done; done
echo "all services are up"
