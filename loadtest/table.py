#!/usr/bin/env python3
"""Prints the recorded steps of one sweep as a markdown table. Usage: table.py PREFIX"""
import csv
import math
import sys

prefix = sys.argv[1]
with open("loadtest/results/summary.csv", newline="") as handle:
    rows = [r for r in csv.DictReader(handle) if r["label"].startswith(prefix + "-")]

def num(value, digits=0):
    number = float(value)
    return "n/a" if math.isnan(number) else f"{number:.{digits}f}"

print(f"### {prefix}")
print("| rate/s | sent | completed | API p95 ms | saga p50 ms | saga p95 ms | saga p99 ms | drain s | outbox lag s | consumer lag | pool pending | dead letters |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    print("| " + " | ".join([r["target_rate"], r["sent"], r["completed"], num(r["api_p95_ms"]), num(r["saga_p50_ms"]),
          num(r["saga_p95_ms"]), num(r["saga_p99_ms"]), r["drain_s"], num(r["outbox_lag_max_s"], 1),
          num(r["consumer_lag_max"]), num(r["db_pool_pending_max"]), num(r["dead_letters"])]) + " |")
