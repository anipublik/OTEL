# otel-starter

**Production-ready OpenTelemetry configs. Clone. Pick. Run.**

---

## What this is

A collection of fully-annotated, runnable OpenTelemetry Collector configs — one per app stack — plus a local Docker observability stack that has Grafana dashboards ready the moment you run `docker compose up`. No prior OTEL knowledge required, because every non-obvious config line has a comment explaining it.

---

## Quick start

```bash
# 1. Clone the repo
git clone https://github.com/anisricode/otel-starter.git && cd otel-starter

# 2. Pick your app stack (example: python)
cp configs/python/collector.yaml infra/docker/collector.yaml

# 3. Bring up the full observability stack
cd infra/docker && docker compose up -d

# 4. Point your app at the Collector
#    Set OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 in your app's env

# 5. Open Grafana — dashboards are already provisioned
open http://localhost:3000   # login: admin / admin
```

Telemetry should be flowing within 60 seconds of step 3.

---

## End-to-end example (Java)

Want to see the full pipeline — app code, custom metrics, Collector, Grafana — without wiring anything yourself?

```bash
./examples/java-payments-service/scripts/run-demo.sh
```

That script:

1. Starts the observability stack with `configs/java/collector.yaml`
2. Builds and runs a Spring Boot **payments-api** service (javaagent, no SDK boilerplate)
3. Generates sample orders and payments so dashboards populate immediately

Then open [http://localhost:3000](http://localhost:3000) (admin / admin):

- **Payments Example** — custom business metrics (`payments.orders.created`, `payments.checkout.duration`, `payments.active_checkouts`)
- **Service Overview — RED** — auto-instrumented HTTP metrics for `payments-api`
- **Traces Explorer** — includes the custom `process-payment` span nested under Spring MVC spans

The example lives in [`examples/java-payments-service/`](examples/java-payments-service/). It shows how custom metrics in `OrderMetrics.java` export via OTLP → Collector metrics pipeline → Prometheus scrape → Grafana. Full walkthrough, API docs, and architecture diagram are in that README.

---

## Pick your stack

| App stack | Collector config | SDK bootstrap | Notes |
|-----------|-----------------|---------------|-------|
| Node.js / Express / Fastify | `configs/node/collector.yaml` | `configs/node/sdk-bootstrap.js` | Uses `@opentelemetry/auto-instrumentations-node`. Covers HTTP, Express, Fastify, gRPC, and DNS spans automatically. |
| Python / FastAPI / Flask | `configs/python/collector.yaml` | `configs/python/sdk-bootstrap.py` | Uses `opentelemetry-distro` + `opentelemetry-instrumentation`. Auto-instruments WSGI, ASGI, requests, SQLAlchemy, redis-py. |
| Java / Spring Boot | `configs/java/collector.yaml` | `configs/java/sdk-bootstrap.java` | Zero-code via `-javaagent`. Drop the jar, set two env vars, done. Covers Spring MVC, JDBC, gRPC, Kafka. |
| Go | `configs/go/collector.yaml` | `configs/go/sdk-bootstrap.go` | Manual SDK setup — Go has no runtime bytecode injection. ~30 lines to a working tracer + meter. |
| .NET | `configs/dotnet/collector.yaml` | `configs/dotnet/sdk-bootstrap.cs` | Auto-instrumentation via NuGet + env vars. Covers ASP.NET Core, HttpClient, SqlClient, gRPC. |
| Kafka | `configs/kafka/collector.yaml` | _(use your app-stack bootstrap)_ | Covers producer and consumer spans with context propagation via message headers. |
| PostgreSQL | `configs/postgres/collector.yaml` | _(use your app-stack bootstrap)_ | `postgresqlreceiver` for DB metrics. Slow-query spans via `sqlcommenter` on the app side. |
| Redis | `configs/redis/collector.yaml` | _(use your app-stack bootstrap)_ | `redisreceiver` for Redis metrics. Command-level spans require app-side instrumentation. |
| Kubernetes | `configs/kubernetes/collector.yaml` | _(use your app-stack bootstrap)_ | `k8s_cluster` receiver, `k8sattributesprocessor` to enrich spans with pod/namespace/node labels. |

**Runnable example:** [`examples/java-payments-service/`](examples/java-payments-service/) — Spring Boot service with custom metrics, spans, and a one-command demo script.

---

## Infra options

| Path | What it gives you | README |
|------|-------------------|--------|
| `infra/docker/` | Full local dev stack: Collector + Prometheus + Tempo + Loki + Grafana. One command. | [infra/docker/README.md](infra/docker/README.md) |
| `infra/kubernetes/` | Raw Kubernetes manifests — DaemonSet + Deployment + RBAC + Service. No Helm required. | [infra/kubernetes/README.md](infra/kubernetes/README.md) |
| `infra/helm/` | Helm chart wrapping the same configs. One `helm install`, per-cloud override files included. | [infra/helm/README.md](infra/helm/README.md) |

---

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md). Configs for new stacks and fixes to existing ones welcome.
