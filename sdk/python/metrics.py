"""
Python custom metrics template — all six OpenTelemetry instrument types.

SETUP: auto.py must be imported first. It initializes the global MeterProvider.
This module shows how to create and use every instrument type from that provider.

REQUIRED ENV VARS (set before starting your app):
  OTEL_SERVICE_NAME             - e.g. "checkout-api"
  OTEL_EXPORTER_OTLP_ENDPOINT  - e.g. "http://localhost:4317"

VERIFY IT'S WORKING:
  docker logs otel-collector --tail 30
  Query Prometheus: http://localhost:9090
  All metrics are prefixed with "otel_" by the Collector's prometheus exporter.

INSTRUMENT TYPES — pick the right one:

  Counter                  Always goes up. Request counts, bytes sent, errors.
  UpDownCounter            Goes up and down. Active connections, queue depth, cache size.
  Histogram                Measures distributions. Latency, payload size, retry count.
  ObservableGauge          Snapshot of a value you poll. Memory, CPU %, config flag.
  ObservableCounter        Monotonically increasing value you poll. Total GC runs, uptime.
  ObservableUpDownCounter  Up/down value you poll. Thread pool size, open file handles.
"""

import os
import time
import threading
from opentelemetry import metrics

# One meter per logical component. The name becomes the instrumentation scope.
meter = metrics.get_meter("com.example.checkout", "1.0.0")


# ─── Counter ──────────────────────────────────────────────────────────────────
# Use when: a value only ever increases and you want to track the rate of change.
# Prometheus query: rate(otel_orders_total[5m])
orders_counter = meter.create_counter(
    "orders",
    description="Total orders submitted",
    unit="1",
)

def record_order(status: str, region: str) -> None:
    # Attributes are the Prometheus label dimensions for this metric.
    # Keep cardinality low: "status" and "region" are fine; "user_id" is not.
    orders_counter.add(1, {"order.status": status, "deployment.region": region})


# ─── UpDownCounter ────────────────────────────────────────────────────────────
# Use when: a value goes both up and down and you need the current level.
# Prometheus query: otel_active_checkouts
active_checkouts = meter.create_up_down_counter(
    "active_checkouts",
    description="Number of checkout sessions currently in progress",
    unit="1",
)

def start_checkout() -> None: active_checkouts.add(1)
def end_checkout()   -> None: active_checkouts.add(-1)


# ─── Histogram ────────────────────────────────────────────────────────────────
# Use when: you need percentile visibility, not just averages.
# Prometheus query: histogram_quantile(0.95, rate(otel_checkout_duration_ms_bucket[5m]))
checkout_duration = meter.create_histogram(
    "checkout.duration",
    description="End-to-end checkout latency in milliseconds",
    unit="ms",
)

def timed_checkout(checkout_fn, method: str):
    start = time.perf_counter()
    try:
        result = checkout_fn()
        elapsed_ms = (time.perf_counter() - start) * 1000
        checkout_duration.record(elapsed_ms, {
            "payment.method": method,
            "checkout.status": "success",
        })
        return result
    except Exception:
        elapsed_ms = (time.perf_counter() - start) * 1000
        checkout_duration.record(elapsed_ms, {
            "payment.method": method,
            "checkout.status": "error",
        })
        raise


# ─── ObservableGauge ──────────────────────────────────────────────────────────
# Use when: you want to report the current value of something at collection time.
# The callback is invoked by the SDK on each export cycle — keep it fast.
# Prometheus query: otel_process_memory_rss_bytes
def _observe_process_memory(options: metrics.CallbackOptions):
    import resource
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # On Linux ru_maxrss is in kilobytes; on macOS it's bytes.
    multiplier = 1 if os.uname().sysname == "Darwin" else 1024
    yield metrics.Observation(rss * multiplier, {"process": "self"})

meter.create_observable_gauge(
    "process.memory.rss",
    callbacks=[_observe_process_memory],
    description="Resident set size of the current process",
    unit="By",
)


# ─── ObservableCounter ────────────────────────────────────────────────────────
# Use when: a counter is maintained externally (e.g., in a library, cache, or
# C extension) and you can only read its current absolute value, not increment it.
# The SDK converts successive absolute values into a rate automatically.
# Prometheus query: rate(otel_http_requests_handled_total[5m])
_total_requests_handled = 0  # updated elsewhere in your application
_lock = threading.Lock()

def _observe_total_requests(options: metrics.CallbackOptions):
    with _lock:
        yield metrics.Observation(_total_requests_handled)

meter.create_observable_counter(
    "http.requests.handled",
    callbacks=[_observe_total_requests],
    description="Total HTTP requests handled since process start",
    unit="1",
)


# ─── ObservableUpDownCounter ──────────────────────────────────────────────────
# Use when: a pool, queue, or connection count is maintained by an external
# library and you need to poll it rather than instrument it inline.
# Prometheus query: otel_db_pool_connections
def _observe_db_pool(options: metrics.CallbackOptions):
    stats = _get_pool_stats()  # your DB pool library's status method
    yield metrics.Observation(stats["active"],  {"pool.state": "active"})
    yield metrics.Observation(stats["idle"],    {"pool.state": "idle"})
    yield metrics.Observation(stats["waiting"], {"pool.state": "waiting"})

meter.create_observable_up_down_counter(
    "db.pool.connections",
    callbacks=[_observe_db_pool],
    description="Database connection pool state by connection status",
    unit="1",
)


# ─── Multi-instrument batch callback ──────────────────────────────────────────
# Read from a single source once and populate multiple instruments.
# Avoids calling an expensive API (e.g., a syscall or HTTP call) separately
# for each instrument.
_heap_gauge      = meter.create_observable_gauge("python.gc.objects",  unit="1")
_gen_gauge       = meter.create_observable_gauge("python.gc.generation", unit="1")

def _observe_gc(options: metrics.CallbackOptions):
    import gc
    counts = gc.get_count()  # (gen0, gen1, gen2) — objects surviving each generation
    _heap_gauge.observe(sum(counts))
    for i, count in enumerate(counts):
        _gen_gauge.observe(count, {"gc.generation": str(i)})

# Note: batch callbacks via create_observable_* with a shared callback are the
# Python idiom — there is no separate BatchObservableCallback API in the Python SDK.


# ─── Placeholder helper (replace with your real pool library's API) ────────────
def _get_pool_stats() -> dict:
    return {"active": 5, "idle": 3, "waiting": 0}
