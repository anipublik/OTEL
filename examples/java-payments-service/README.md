# Java payments service — end-to-end OTEL example

A minimal Spring Boot service that emits **traces**, **custom metrics**, and **logs** through the otel-starter Collector pipeline into Grafana.

This is the "whole nine yards" walkthrough: application code → javaagent → Collector → Prometheus/Tempo/Loki → pre-built dashboards.

---

## What this example shows

| Signal | Source | What you'll see in Grafana |
|--------|--------|---------------------------|
| Traces | Spring MVC auto-instrumentation + custom `process-payment` span | Tempo → Traces Explorer |
| HTTP metrics | javaagent (`http.server.request.duration`) | Service Overview — RED dashboard |
| Custom metrics | `OrderMetrics` class (counter, histogram, gauge) | Payments Example dashboard |
| Logs | Spring Boot logging bridged to OTLP | Loki (via Collector) |
| Collector health | Collector internal metrics | Collector Health dashboard |

---

## Architecture

```
┌─────────────────┐   OTLP/gRPC    ┌──────────────┐   scrape    ┌────────────┐
│  payments-api   │ ──────────────►│  Collector   │◄────────────│ Prometheus │
│  (Spring Boot   │    :4317       │              │   :8889     └─────┬──────┘
│   + javaagent)  │                │  processors: │                   │
└─────────────────┘                │  memory_limiter                   │
                                   │  batch                           ▼
                                   │  resourcedetection         ┌──────────┐
                                   └──────┬───────┬─────────────►│ Grafana  │
                                          │       │              └──────────┘
                                          ▼       ▼
                                       Tempo    Loki
                                      (traces)  (logs)
```

Custom metrics flow: `OrderMetrics` → OTLP → Collector `metrics` pipeline → `prometheus` exporter → Prometheus scrape → Grafana.

---

## Prerequisites

- Docker Desktop (or Docker Engine + Compose)
- Java 17+ and Maven 3.9+ (only if running the app outside Docker)

---

## Quick start (Docker — recommended)

From the repo root:

```bash
# 1. Start the observability stack with the Java collector config
cp configs/java/collector.yaml infra/docker/collector.yaml
docker compose -f infra/docker/docker-compose.yml up -d

# 2. Build and run the payments service (joins the otel-starter network)
cd examples/java-payments-service
docker compose up --build -d

# 3. Generate traffic
./scripts/generate-traffic.sh

# 4. Open Grafana
open http://localhost:3000   # admin / admin
#   → Dashboards → "Payments Example"
#   → Dashboards → "Service Overview — RED" (filter service_name = payments-api)
```

Within ~60 seconds you should see custom metric panels updating and traces in Tempo.

---

## Quick start (local JVM)

If you prefer running the app on your host machine:

```bash
# Terminal 1 — observability stack
cp configs/java/collector.yaml infra/docker/collector.yaml
docker compose -f infra/docker/docker-compose.yml up -d

# Terminal 2 — download javaagent (once)
curl -L https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar \
  -o examples/java-payments-service/opentelemetry-javaagent.jar

# Terminal 2 — run the app
cd examples/java-payments-service
export OTEL_SERVICE_NAME=payments-api
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp

mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-javaagent:./opentelemetry-javaagent.jar"
```

Then generate traffic:

```bash
./scripts/generate-traffic.sh http://localhost:8080
```

---

## API endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Liveness check (excluded from trace noise) |
| `POST` | `/api/orders` | Create an order — records `payments.orders.created` counter |
| `POST` | `/api/orders/{id}/pay` | Process payment — custom span + duration histogram |
| `GET` | `/api/orders/{id}` | Fetch order status |
| `POST` | `/api/orders/{id}/fail` | Simulate payment failure — increments error counter |

Example:

```bash
# Create an order
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-42","amountUsd":99.99,"item":"annual-subscription"}'

# Pay it (replace ORDER_ID)
curl -s -X POST http://localhost:8080/api/orders/ORDER_ID/pay
```

---

## Custom metrics (the interesting part)

All custom metrics live in `OrderMetrics.java` and use the OpenTelemetry Metrics API.
The javaagent registers `GlobalOpenTelemetry` — your code just calls it.

| Metric | Type | Attributes | Purpose |
|--------|------|------------|---------|
| `payments.orders.created` | Counter | `payments.status` | Orders created vs failed |
| `payments.checkout.duration` | Histogram | `payments.status`, `payments.method` | Checkout latency in ms |
| `payments.active_checkouts` | UpDownCounter | — | In-flight checkouts (concurrency signal) |

In Prometheus (via the Collector's prometheus exporter), query:

```promql
rate(otel_payments_orders_created_total[5m])
histogram_quantile(0.95, sum by (le) (rate(otel_payments_checkout_duration_milliseconds_bucket[5m])))
otel_payments_active_checkouts
```

Metric names are prefixed with `otel_` because the Collector prometheus exporter sets `namespace: otel`.

---

## Custom spans

`OrderService.processPayment()` creates a child span named `process-payment` with attributes:

- `payments.order_id`
- `payments.amount_usd`
- `payments.method`

These appear nested under the auto-instrumented Spring MVC span in Tempo.

---

## Verify telemetry is flowing

```bash
# Collector debug output — you should see spans and metric data points
docker logs otel-collector --tail 50

# Prometheus — custom metrics should appear within ~30s
curl -s 'http://localhost:9090/api/v1/query?query=otel_payments_orders_created_total' \
  2>/dev/null || echo "Prometheus not exposed to host — check via Grafana Explore instead"
```

If spans appear in Collector logs but not Grafana, wait 30–60s for the first metric scrape cycle.

---

## Stop everything

```bash
cd examples/java-payments-service && docker compose down
docker compose -f infra/docker/docker-compose.yml down
```

---

## File map

```
examples/java-payments-service/
├── README.md                          ← you are here
├── docker-compose.yml                 ← runs payments-api on the otel-starter network
├── Dockerfile                         ← multi-stage build with javaagent baked in
├── pom.xml
├── scripts/
│   ├── run-demo.sh                    ← one command: stack + app + traffic
│   └── generate-traffic.sh            ← curl loop to populate dashboards
└── src/main/java/com/example/payments/
    ├── PaymentsApplication.java
    ├── controller/OrderController.java
    ├── model/Order.java
    ├── service/OrderService.java
    └── telemetry/OrderMetrics.java    ← custom metrics + span helpers
```
