#!/usr/bin/env python3
"""Collects the numbers for one load-test step from k6's summary and from Prometheus.

Usage: collect.py LABEL RATE START_EPOCH END_EPOCH DRAIN_SECONDS STILL_PENDING
"""
import csv
import json
import math
import os
import sys
import urllib.parse
import urllib.request

PROMETHEUS = "http://localhost:9090/api/v1/query"
label, rate, start, end, drain, still_pending, completed_db = sys.argv[1:8]
window = int(math.ceil(float(end) - float(start))) + 10


def query(expression):
    url = PROMETHEUS + "?" + urllib.parse.urlencode({"query": expression})
    with urllib.request.urlopen(url) as response:
        result = json.load(response)["data"]["result"]
    return float(result[0]["value"][1]) if result else float("nan")


def metric(name):
    with open(f"loadtest/results/{label}-k6.json") as handle:
        entry = json.load(handle)["metrics"][name]
    return entry.get("values", entry)  # older and newer k6 summary layouts


duration = metric("http_req_duration")
sent = int(metric("http_reqs")["count"])
saga = lambda p: 1000 * query(f"histogram_quantile({p}, sum by (le) (increase(orders_saga_duration_seconds_bucket[{window}s])))")

row = {
    "label": label,
    "target_rate": rate,
    "sent": sent,
    "completed": int(completed_db),  # counted in the database: Prometheus increase() misses the first increment of a new series
    "api_p95_ms": duration["p(95)"],
    "api_p99_ms": duration["p(99)"],
    "saga_p50_ms": saga(0.5),
    "saga_p95_ms": saga(0.95),
    "saga_p99_ms": saga(0.99),
    "drain_s": int(drain),
    "outbox_lag_max_s": query(f"max(max_over_time(outbox_oldest_age_seconds[{window}s]))"),
    "consumer_lag_max": query(f"max(max_over_time(kafka_consumer_fetch_manager_records_lag_max[{window}s]))"),
    "db_pool_pending_max": query(f"max(max_over_time(hikaricp_connections_pending[{window}s]))"),
    "dead_letters": query(f"sum(increase(consumer_dead_letters_total[{window}s])) or vector(0)"),
    "still_pending": int(still_pending),
}


def show(value, digits=0):
    return "n/a" if isinstance(value, float) and math.isnan(value) else (f"{value:.{digits}f}" if isinstance(value, float) else str(value))


print()
print("| step | rate/s | sent | completed | API p95 ms | API p99 ms | saga p50 ms | saga p95 ms | saga p99 ms | drain s | outbox lag s | consumer lag | pool pending | dead letters |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
print("| " + " | ".join([label, rate, show(sent), show(row["completed"]), show(row["api_p95_ms"]), show(row["api_p99_ms"]),
      show(row["saga_p50_ms"]), show(row["saga_p95_ms"]), show(row["saga_p99_ms"]), show(row["drain_s"]),
      show(row["outbox_lag_max_s"], 1), show(row["consumer_lag_max"]), show(row["db_pool_pending_max"]),
      show(row["dead_letters"])]) + " |")
if row["still_pending"] != 0:
    print(f"\nWARNING: {row['still_pending']} orders were still PENDING when the wait ended: the system did not drain.")

summary = "loadtest/results/summary.csv"
is_new = not os.path.exists(summary)
with open(summary, "a", newline="") as handle:
    writer = csv.DictWriter(handle, fieldnames=list(row))
    if is_new:
        writer.writeheader()
    writer.writerow(row)
