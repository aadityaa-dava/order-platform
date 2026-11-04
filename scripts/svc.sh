#!/usr/bin/env bash
# Stop (kill -9, a real crash) or start ONE service. Usage: scripts/svc.sh stop|start SERVICE PORT
cd "$(dirname "$0")/.." || exit 1
case $1 in
  stop) lsof -ti tcp:"$3" -sTCP:LISTEN | xargs kill -9 2> /dev/null; sleep 2 ;;
  start)
    mkdir -p logs
    export LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs MANAGEMENT_TRACING_SAMPLING_PROBABILITY=${SAMPLING:-0.0}
    nohup ./mvnw -q -pl services/"$2" spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m -Xms128m" > logs/"$2".log 2>&1 &
    until curl -sf localhost:"$3"/actuator/health > /dev/null; do sleep 2; done ;;
esac
