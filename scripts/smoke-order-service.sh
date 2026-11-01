#!/usr/bin/env bash
# Exercises order-service end to end: create, idempotent replay, key reuse, validation, lookup.
set -u
BASE=${BASE:-http://localhost:8081}
KEY="smoke-$(date +%s)"
BODY='{"customerId":"cust-1","customerEmail":"jane@example.com","currency":"USD","items":[{"sku":"SKU-RED-MUG","quantity":2,"unitPriceMinor":1250}]}'
OTHER='{"customerId":"cust-1","customerEmail":"jane@example.com","currency":"USD","items":[{"sku":"SKU-RED-MUG","quantity":3,"unitPriceMinor":1250}]}'
BAD='{"customerId":"","customerEmail":"not-an-email","currency":"usd","items":[]}'
id_of() { sed -n 's/.*"id":"\([^"]*\)".*/\1/p' /tmp/smoke.body; }
post() { curl -s -o /tmp/smoke.body -w "%{http_code}" -X POST "$BASE/orders" -H "Content-Type: application/json" "$@"; }

echo "1) create                 -> $(post -H "Idempotency-Key: $KEY" -d "$BODY")   (expect 202)"
ID1=$(id_of)
echo "2) replay same key+body   -> $(post -H "Idempotency-Key: $KEY" -d "$BODY")   (expect 202)"
ID2=$(id_of)
echo "   same order returned?   -> $([ "$ID1" = "$ID2" ] && echo yes || echo NO)   (expect yes)"
echo "3) same key, other body   -> $(post -H "Idempotency-Key: $KEY" -d "$OTHER")   (expect 422)"
echo "4) missing Idempotency-Key-> $(post -d "$BODY")   (expect 400)"
echo "5) invalid body           -> $(post -H "Idempotency-Key: $KEY-bad" -d "$BAD")   (expect 400)"
cat /tmp/smoke.body; echo
echo "6) get order              -> $(curl -s -o /tmp/smoke.body -w '%{http_code}' "$BASE/orders/$ID1")   (expect 200)"
echo "   status                 -> $(sed -n 's/.*"status":"\([^"]*\)".*/\1/p' /tmp/smoke.body)   (expect PENDING)"
echo "7) unknown order          -> $(curl -s -o /dev/null -w '%{http_code}' "$BASE/orders/00000000-0000-0000-0000-000000000000")   (expect 404)"
