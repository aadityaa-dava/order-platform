#!/usr/bin/env bash
# Runs the same workload at increasing rates. Usage: loadtest/sweep.sh PREFIX RATE [RATE...]
# Example: loadtest/sweep.sh baseline 10 20 30 40 60
set -u
cd "$(dirname "$0")/.."
PREFIX=$1
shift

echo "warm-up (10 orders/s for 30s; not part of the results)"
./loadtest/run-step.sh "warmup-$PREFIX" 10 30 > /dev/null 2>&1

for rate in "$@"; do
  echo "== $PREFIX at $rate orders/s (40s of load, then wait for the saga to drain)"
  ./loadtest/run-step.sh "$PREFIX-$rate" "$rate" 40 | grep -E "^\| $PREFIX-|WARNING"
done
echo
python3 -I loadtest/table.py "$PREFIX"
