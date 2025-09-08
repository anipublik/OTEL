"""
Python auto-instrumentation — zero-code OTEL setup.

IMPORT THIS AS THE VERY FIRST IMPORT in your app's entry point:

  import auto   # or: from sdk.python import auto
  from fastapi import FastAPI
  ...

REQUIRED ENV VARS:
  OTEL_SERVICE_NAME             - your service name (e.g. "api-gateway")
  OTEL_EXPORTER_OTLP_ENDPOINT  - Collector address (e.g. "http://localhost:4317")

OPTIONAL ENV VARS:
  OTEL_RESOURCE_ATTRIBUTES     - "deployment.environment=local,service.version=1.0.0"
  OTEL_PYTHON_EXCLUDED_URLS    - "/health,/readyz" to suppress health check spans

ALTERNATIVELY — use the zero-code launcher (no import needed):
  opentelemetry-instrument uvicorn app:app --host 0.0.0.0 --port 8080

VERIFY IT'S WORKING:
  Make one request, then: docker logs otel-collector --tail 30
"""

import os
import atexit

from opentelemetry import trace, metrics
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource, SERVICE_NAME
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter

_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")
_service_name = os.environ.get("OTEL_SERVICE_NAME", "unknown-python-service")

resource = Resource.create({SERVICE_NAME: _service_name})

# Traces
_tracer_provider = TracerProvider(resource=resource)
_tracer_provider.add_span_processor(
    BatchSpanProcessor(OTLPSpanExporter(endpoint=_endpoint))
)
trace.set_tracer_provider(_tracer_provider)

# Metrics
_meter_provider = MeterProvider(
    resource=resource,
    metric_readers=[
        PeriodicExportingMetricReader(
            OTLPMetricExporter(endpoint=_endpoint),
            export_interval_millis=30_000,
        )
    ],
)
metrics.set_meter_provider(_meter_provider)

# Auto-instrumentation: activate all detected library instrumentations.
# This covers FastAPI, Flask, Django, SQLAlchemy, redis-py, requests, httpx, etc.
# Only runs if opentelemetry-distro is installed.
try:
    from opentelemetry.instrumentation.auto_instrumentation import initialize
    initialize()
except ImportError:
    pass

atexit.register(_tracer_provider.shutdown)
atexit.register(_meter_provider.shutdown)
