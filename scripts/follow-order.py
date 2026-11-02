#!/usr/bin/env python3
"""Prints one order's timeline across all services, merged from their JSON logs.

Usage: ./scripts/follow-order.py <orderId or correlationId>
"""
import glob
import json
import sys

def dig(event, dotted):
    """Reads 'service.name' whether the JSON is flat ({"service.name": ..}) or nested ({"service": {"name": ..}})."""
    if dotted in event:
        return event[dotted]
    value = event
    for part in dotted.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
needle = sys.argv[1]

rows = []
for path in glob.glob("logs/*.log"):
    with open(path, errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if line.startswith("{") and needle in line:
                try:
                    rows.append(json.loads(line))
                except ValueError:
                    pass

rows.sort(key=lambda e: e.get("@timestamp", ""))
if not rows:
    raise SystemExit("No log lines found for " + needle + ". Were the services started with scripts/run-services.sh?")

print(f"{'time':<13}{'service':<22}{'level':<6} message")
for e in rows:
    time = str(e.get("@timestamp", ""))[11:23]
    service = dig(e, "service.name") or "?"
    level = dig(e, "log.level") or "?"
    print(f"{time:<13}{service:<22}{level:<6} {e.get('message', '')[:140]}")
