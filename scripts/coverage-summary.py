#!/usr/bin/env python3
"""Summarises JaCoCo coverage per module and per area. Run after `./mvnw verify`.

Core areas hold the business logic that the 80% target applies to; the rest is wiring and data carriers.
"""
import collections
import csv
import glob
import os

CORE = {"application", "domain", "gateway", "email", "stock", "reservation", "persistence", "messaging", "contracts",
        "idempotency", "outbox", "lib"}
KNOWN = CORE | {"api", "messaging", "events"}
THRESHOLD = 80.0

# (module, area) -> [line_missed, line_covered, branch_missed, branch_covered]
totals = collections.defaultdict(lambda: [0, 0, 0, 0])
files = sorted(glob.glob("services/*/target/site/jacoco/jacoco.csv") + glob.glob("libs/*/target/site/jacoco/jacoco.csv"))
if not files:
    raise SystemExit("No JaCoCo reports found. Run ./mvnw verify first.")

for path in files:
    module = path.split(os.sep)[1]
    is_lib = path.startswith("libs")
    with open(path, newline="") as handle:
        for row in csv.DictReader(handle):
            last = row["PACKAGE"].split(".")[-1]
            area = "lib" if is_lib else (last if last in KNOWN else "root")
            if area == "lib" and module == "event-contracts":
                area = "contracts"
            cell = totals[(module, area)]
            cell[0] += int(row["LINE_MISSED"]); cell[1] += int(row["LINE_COVERED"])
            cell[2] += int(row["BRANCH_MISSED"]); cell[3] += int(row["BRANCH_COVERED"])

def pct(missed, covered):
    total = missed + covered
    return 100.0 * covered / total if total else float("nan")

print(f"{'module':<22}{'area':<14}{'lines':>8}{'branches':>10}   core")
print("-" * 62)
core_by_module = collections.defaultdict(lambda: [0, 0])
for (module, area), (lm, lc, bm, bc) in sorted(totals.items()):
    is_core = area in CORE
    if is_core:
        core_by_module[module][0] += lm
        core_by_module[module][1] += lc
    branches = "-" if bm + bc == 0 else f"{pct(bm, bc):.0f}%"
    print(f"{module:<22}{area:<14}{pct(lm, lc):>7.0f}%{branches:>10}   {'*' if is_core else ''}")

print("\nCORE LOGIC LINE COVERAGE (target: 80%)")
for module, (missed, covered) in sorted(core_by_module.items()):
    value = pct(missed, covered)
    print(f"  {module:<22}{value:>6.1f}%   {'ok' if value >= THRESHOLD else 'BELOW TARGET'}")
