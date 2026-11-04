#!/usr/bin/env bash
# Stops whatever is listening on the four service ports.
for p in 8081 8082 8083 8084; do lsof -ti tcp:"$p" -sTCP:LISTEN | xargs kill 2> /dev/null; done
sleep 3
