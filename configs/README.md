# configs/

One directory per app stack. Each directory contains:

- `README.md` — setup steps specific to that stack
- `collector.yaml` — a complete, self-contained OpenTelemetry Collector config
- `sdk-bootstrap.<ext>` — the ~20 lines you paste into your app's entry point

---

## Which config do I pick?

**Start with your app's language:**

| I'm building... | Go to |
|----------------|-------|
| A Node.js service (Express, Fastify, Koa, raw http) | `configs/node/` |
| A Python service (FastAPI, Flask, Django) | `configs/python/` |
| A Java service (Spring Boot, Micronaut, Quarkus) | `configs/java/` |
| A Go service | `configs/go/` |
| A .NET service (ASP.NET Core) | `configs/dotnet/` |
| A Kafka producer or consumer | `configs/kafka/` |
| PostgreSQL metrics and slow-query tracing | `configs/postgres/` |
| Redis metrics and command-level tracing | `configs/redis/` |
| Everything running in Kubernetes | `configs/kubernetes/` |

For cloud-specific backends (CloudWatch, X-Ray, Cloud Trace, Azure Monitor), see `configs/cloud/`.

---

## How to use these configs

1. Copy the `collector.yaml` for your stack to `infra/docker/collector.yaml` (overwriting the default).
2. Start the infra stack: `cd infra/docker && docker compose up -d`.
3. Add the SDK bootstrap snippet to your app's entry point.
4. Set the two env vars the bootstrap snippet requires.
5. Run your app. Telemetry flows to Grafana at `http://localhost:3000`.

---

## All configs share the same structure

Every `collector.yaml` in this directory has:

- **receivers** — `otlpreceiver` (gRPC on 4317, HTTP on 4318) plus stack-specific receivers
- **processors** — `memory_limiter` first (prevents OOM), then `resourcedetection/k8s_api`, then `batch`
- **exporters** — `debug` (always, for local troubleshooting) plus Prometheus, Tempo, Loki
- **extensions** — `health_check` (Collector's own `/health` endpoint) and `pprof`
- **service/pipelines** — explicit traces, metrics, and logs pipelines

The `memory_limiter` is always first in the processor chain. If it's not first, the Collector can OOM under load because backpressure doesn't kick in until data has already been accepted and buffered.
