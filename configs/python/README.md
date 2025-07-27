# Python / FastAPI / Flask — OpenTelemetry setup

This config covers Python WSGI and ASGI HTTP services. It works with FastAPI, Flask, Django, Starlette, and any framework that uses those primitives.

---

## Prerequisites

- Python 3.8+
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Install the SDK packages

```bash
pip install \
  opentelemetry-distro \
  opentelemetry-exporter-otlp-proto-grpc \
  opentelemetry-instrumentation-fastapi \
  opentelemetry-instrumentation-flask \
  opentelemetry-instrumentation-django \
  opentelemetry-instrumentation-sqlalchemy \
  opentelemetry-instrumentation-redis \
  opentelemetry-instrumentation-requests \
  opentelemetry-instrumentation-httpx
```

Or let `opentelemetry-bootstrap` detect and install everything for your environment:

```bash
pip install opentelemetry-distro
opentelemetry-bootstrap --action=install
```

## Two ways to run with auto-instrumentation

**Option 1 — zero-code (preferred for FastAPI/Flask/Django):**

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0

opentelemetry-instrument python app.py
# or: opentelemetry-instrument uvicorn app:app --host 0.0.0.0 --port 8000
```

**Option 2 — code-based (more control, better for custom spans):**

Copy `sdk-bootstrap.py` and import it at the top of your entry point.

## Verify it's working

Make one HTTP request to your app, then:

```bash
docker logs otel-collector --tail 50
```

You should see `ScopeSpans` records with your service name and HTTP route spans. If you see nothing, confirm `OTEL_EXPORTER_OTLP_ENDPOINT` is set and the Collector container is running.

---

## What gets instrumented automatically

`opentelemetry-bootstrap --action=install` detects installed packages and adds their instrumentations. The most important ones:

- `fastapi`, `flask`, `django`, `starlette` — inbound HTTP request spans
- `requests`, `httpx`, `aiohttp` — outbound HTTP spans
- `sqlalchemy`, `psycopg2` — database query spans
- `redis`, `pymongo`, `elasticsearch-py` — storage client spans
- `celery`, `grpc` — task queue and RPC spans

---

## Collector config notes

`collector.yaml` in this directory is identical in structure to `configs/node/collector.yaml`. Python sends OTLP/gRPC to port 4317 by default. If your deployment can't use gRPC, set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` and point at port 4318.
