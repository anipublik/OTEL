#!/usr/bin/env bash
# One-command demo: observability stack + payments-api + sample traffic.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXAMPLE="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Copying Java collector config"
cp "$ROOT/configs/java/collector.yaml" "$ROOT/infra/docker/collector.yaml"

echo "==> Starting observability stack"
docker compose -f "$ROOT/infra/docker/docker-compose.yml" up -d

echo "==> Waiting for Collector health (up to 60s)"
for i in $(seq 1 30); do
  if docker exec otel-collector wget -q -O- http://localhost:13133/health >/dev/null 2>&1; then
    echo "    Collector is healthy"
    break
  fi
  sleep 2
done

echo "==> Building and starting payments-api"
docker compose -f "$EXAMPLE/docker-compose.yml" up --build -d

echo "==> Waiting for payments-api (up to 60s)"
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/health >/dev/null 2>&1; then
    echo "    payments-api is healthy"
    break
  fi
  sleep 2
done

echo "==> Generating sample traffic"
"$EXAMPLE/scripts/generate-traffic.sh" http://localhost:8080

echo ""
echo "Done. Open Grafana: http://localhost:3000 (admin / admin)"
echo "  Dashboards → Payments Example"
echo "  Dashboards → Service Overview — RED (service_name = payments-api)"
echo ""
echo "Stop with:"
echo "  docker compose -f $EXAMPLE/docker-compose.yml down"
echo "  docker compose -f $ROOT/infra/docker/docker-compose.yml down"
