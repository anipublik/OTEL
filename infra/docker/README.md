# infra/docker/ — local development observability stack

`docker compose up -d` brings up the full observability stack. No extra steps.

---

## What you get

| Service | Image | Ports | Purpose |
|---------|-------|-------|---------|
| `collector` | `otel/opentelemetry-collector-contrib:latest` | 4317, 4318, 8889 | Receives all telemetry from your apps |
| `prometheus` | `prom/prometheus:latest` | 9090 (internal) | Scrapes Collector metrics endpoint |
| `tempo` | `grafana/tempo:latest` | 3200, 4317 (internal) | Stores traces |
| `loki` | `grafana/loki:latest` | 3100 (internal) | Stores logs |
| `grafana` | `grafana/grafana:latest` | 3000 | Dashboards — already provisioned |

All services share the `otel` Docker network. Only Grafana (3000), Collector OTLP gRPC (4317), and Collector OTLP HTTP (4318) are exposed to the host. Prometheus, Tempo, and Loki are internal.

---

## Prerequisites

- Docker Desktop (Mac/Windows) or Docker Engine + Compose plugin (Linux)
- 2GB+ free RAM (the full stack uses ~1.5GB at idle)

## Quick start

```bash
cd infra/docker

# (Optional) Copy your app stack's collector.yaml over the default:
# cp ../../configs/python/collector.yaml collector.yaml

docker compose up -d

# Check all services are healthy:
docker compose ps

# Watch Collector logs to confirm telemetry is flowing:
docker logs otel-collector -f
```

Open Grafana: [http://localhost:3000](http://localhost:3000)  
Default login: **admin / admin**

---

## Three pre-built dashboards

All dashboards are provisioned automatically — they're there the moment Grafana starts.

**Service Overview** — RED dashboard (Rate / Errors / Duration) per service. Template variables for `service_name` and `deployment_environment` so it works for any app immediately.

**Collector Health** — The Collector's own internal metrics: accepted spans/s, dropped data points, queue depth, exporter queue length, memory usage. Look here first when telemetry stops flowing.

**Traces Explorer** — Trace volume heatmap and p50/p95/p99 latency from Tempo. Not a trace viewer (use Tempo's native UI at [http://localhost:3200](http://localhost:3200)) but a high-level view for spotting latency regressions.

**Payments Example** — Custom business metrics from [`examples/java-payments-service/`](../../examples/java-payments-service/). Run `./examples/java-payments-service/scripts/run-demo.sh` to populate it.

---

## Customizing the Collector config

The `collector.yaml` in this directory is the default config. To use a per-app-stack config:

```bash
cp ../../configs/node/collector.yaml collector.yaml
docker compose restart collector
```

The Collector container mounts `./collector.yaml` — editing it and restarting the container picks up the changes immediately.

---

## Adding TLS (production path)

By default, all connections inside the Docker network are unencrypted. This is fine for local development. For production:

1. Generate certificates (or use cert-manager in K8s).
2. Add `tls:` blocks to receiver and exporter configs.
3. Mount the certificate files into the Collector container.

See the Collector docs for TLS configuration: [opentelemetry.io/docs/collector/configuration/#tls](https://opentelemetry.io/docs/collector/configuration/#tls)

---

## Stopping the stack

```bash
docker compose down           # stop, keep data volumes
docker compose down -v        # stop, delete all data volumes
```
