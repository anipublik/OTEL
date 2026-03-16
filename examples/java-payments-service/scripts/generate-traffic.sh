#!/usr/bin/env bash
# Generate sample orders and payments so Grafana dashboards have data to show.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
REQUESTS="${2:-20}"

echo "Generating $REQUESTS orders against $BASE_URL"

for i in $(seq 1 "$REQUESTS"); do
  ORDER=$(curl -sf -X POST "$BASE_URL/api/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"cust-$i\",\"item\":\"subscription-tier-$((i % 3))\",\"amountUsd\":$((10 + i))}")

  ORDER_ID=$(echo "$ORDER" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

  if [ -z "$ORDER_ID" ]; then
    echo "  [$i] failed to create order"
    continue
  fi

  # ~80% success, ~20% simulated failures — populates both metric label values.
  if [ $((i % 5)) -eq 0 ]; then
    curl -sf -X POST "$BASE_URL/api/orders/$ORDER_ID/fail" >/dev/null || true
    echo "  [$i] order $ORDER_ID → failed (expected)"
  else
    curl -sf -X POST "$BASE_URL/api/orders/$ORDER_ID/pay" >/dev/null
    echo "  [$i] order $ORDER_ID → paid"
  fi

  sleep 0.3
done

echo "Done. Metrics should appear in Grafana within ~30s."
