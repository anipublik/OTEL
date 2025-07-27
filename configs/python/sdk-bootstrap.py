"""
OpenTelemetry SDK bootstrap for Python.

IMPORT THIS MODULE as the very first import in your app's entry point —
before any framework or library that might make network calls.

  # main.py or app.py
  import sdk_bootstrap   # must be first
  from fastapi import FastAPI
  ...

REQUIRED ENV VARS — set these before starting your app:

  OTEL_SERVICE_NAME              - shown on every span, metric, and log (e.g. "payments-api")
  OTEL_EXPORTER_OTLP_ENDPOINT   - where the Collector listens (e.g. "http://localhost:4317")

OPTIONAL ENV VARS:

  OTEL_RESOURCE_ATTRIBUTES       - extra key=value pairs on every signal
                                   e.g. "deployment.environment=local,service.version=1.0.0"
  OTEL_PYTHON_EXCLUDED_URLS      - comma-separated URL patterns to suppress spans for
                                   e.g. "/healthz,/readyz" to skip health-check noise

VERIFY IT'S WORKING:
  Make one request to your app, then:
    docker logs otel-collector --tail 30
  Look for ScopeSpans with your service name and HTTP method/route.
"""

import os
import atexit

from opentelemetry import trace, metrics
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource, SERVICE_NAME, SERVICE_VERSION
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter

# The SDK reads OTEL_SERVICE_NAME and OTEL_EXPORTER_OTLP_ENDPOINT from the environment.
# We also read them explicitly here so the bootstrap fails fast with a clear error
# if they're missing, rather than silently sending to the wrong endpoint.
_service_name = os.environ.get("OTEL_SERVICE_NAME", "unknown-python-service")
_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317")

# Resource: attributes that appear on every signal this process emits.
resource = Resource.create(
    {
        SERVICE_NAME: _service_name,
        SERVICE_VERSION: os.environ.get("SERVICE_VERSION", "0.0.0"),
    }
)

# --- Traces ---
_trace_exporter = OTLPSpanExporter(endpoint=_endpoint)

# BatchSpanProcessor buffers spans and exports them in bulk.
# SimpleSpanProcessor (synchronous) is fine for scripts but adds latency per span in servers.
_tracer_provider = TracerProvider(resource=resource)
_tracer_provider.add_span_processor(BatchSpanProcessor(_trace_exporter))
trace.set_tracer_provider(_tracer_provider)

# --- Metrics ---
_metric_exporter = OTLPMetricExporter(endpoint=_endpoint)
_metric_reader = PeriodicExportingMetricReader(
    _metric_exporter,
    # export_interval_millis: how often metrics are pushed to the Collector.
    # 30s is fine for most services. Lower if you need sub-minute alerting.
    export_interval_millis=30_000,
)
_meter_provider = MeterProvider(resource=resource, metric_readers=[_metric_reader])
metrics.set_meter_provider(_meter_provider)

# Flush pending telemetry on clean shutdown so the last spans aren't lost.
atexit.register(_tracer_provider.shutdown)
atexit.register(_meter_provider.shutdown)

# --- Auto-instrumentation ---
# If you ran `opentelemetry-instrument` as a wrapper command, auto-instrumentation
# is already active and you can skip the lines below. If you're importing this
# module directly, call configure_once() to enable it programmatically.

def configure_once():
    """
    Enable auto-instrumentation for all detected libraries.
    Call this once, after all libraries have been imported.
    Only needed when NOT using `opentelemetry-instrument` wrapper.
    """
    try:
        from opentelemetry.instrumentation.auto_instrumentation import (
            initialize,
        )
        initialize()
    except ImportError:
        # opentelemetry-distro not installed — auto-instrumentation unavailable.
        # Install it with: pip install opentelemetry-distro && opentelemetry-bootstrap -a install
        pass
