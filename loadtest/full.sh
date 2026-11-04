#!/usr/bin/env bash
# The complete experiment: before, fix 1 (parallelism), fix 2 (outbox poll interval).
cd "$(dirname "$0")/.."
echo "##### BEFORE: 3 partitions, 3 consumer threads per listener, outbox poll 200 ms"
./scripts/start-services.sh
./loadtest/sweep.sh before 10 20 30 40
echo
./loadtest/after.sh
