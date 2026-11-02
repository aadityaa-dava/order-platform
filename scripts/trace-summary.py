#!/usr/bin/env python3
"""Lists recent traces from Jaeger: how many traces the recent activity produced, and what each contains.

Usage: ./scripts/trace-summary.py [minutes, default 10]
"""
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "http://localhost:16686/api"
SERVICES = ["order-service", "inventory-service", "payment-service", "notification-service"]
minutes = int(sys.argv[1]) if len(sys.argv) > 1 else 10

def get(path):
    with urllib.request.urlopen(BASE + path) as response:
        return json.load(response)["data"] or []

now_us = int(time.time() * 1_000_000)
start_us = now_us - minutes * 60 * 1_000_000
traces = {}
for service in SERVICES:
    query = urllib.parse.urlencode({"service": service, "start": start_us, "end": now_us, "limit": 200})
    for trace in get("/traces?" + query):
        traces[trace["traceID"]] = trace

rows = []
for trace_id, trace in traces.items():
    spans, processes = trace["spans"], trace["processes"]
    first = min(spans, key=lambda s: s["startTime"])
    services = sorted({processes[s["processID"]]["serviceName"].replace("-service", "") for s in spans})
    root = f"{processes[first['processID']]['serviceName'].replace('-service', '')}: {first['operationName']}"
    rows.append((first["startTime"], trace_id[:8], root, ",".join(services), len(spans)))
rows.sort()

print(f"{len(rows)} trace(s) in the last {minutes} minute(s)\n")
print(f"{'trace':<10}{'first span':<46}{'services':<40}{'spans':>5}")
for _, trace_id, root, services, count in rows:
    print(f"{trace_id:<10}{root[:44]:<46}{services:<40}{count:>5}")
