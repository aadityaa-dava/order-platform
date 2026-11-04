# Demo video script (about 4 minutes)

Preparation: infrastructure and services running with `SAMPLING=1.0`, Grafana dashboard open in a tab, Jaeger open in a tab, a terminal with a large font.

1. **0:00 What this is (20 s).** "Four services that keep data consistent without distributed transactions. I will show the happy path, then break it."
2. **0:20 Architecture (30 s).** Show the diagram in the README. Say: each service owns its database; events go through Kafka; every event is written through an outbox in the same transaction as the state change.
3. **0:50 Happy path (50 s).** Run the `curl` from the quick start. Show `PENDING`, then `CONFIRMED`. Open the trace in Jaeger and point out that it continues across the outbox hop. Show the same `Idempotency-Key` again returning the same order.
4. **1:40 Break it (60 s).** Run `./scripts/svc.sh stop payment-service 8083`. Send 20 orders; show they stay `PENDING` and the API still answers. Run `./scripts/svc.sh start payment-service 8083` and show them all turn `CONFIRMED`. Say: the events waited in Kafka, nothing was lost or duplicated.
5. **2:40 Numbers (40 s).** Show the benchmark table: "22 to 85 orders per second; I found the bottleneck with Little's law, not by guessing." Show the Grafana panel during a load test.
6. **3:20 Honesty (30 s).** Show `docs/what-broke.md` and the limitations section: "no auto-cancel because it needs a refund flow; I measured the gap instead of shipping a half-correct fix."
7. **3:50 Close (10 s).** CI badge, ADRs, repository link.
