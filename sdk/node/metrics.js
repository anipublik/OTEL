/**
 * Node.js custom metrics template — all six OpenTelemetry instrument types.
 *
 * SETUP: auto.js must be loaded first. It initializes the global MeterProvider.
 * This file shows how to create and use every instrument type from that provider.
 *
 * REQUIRED ENV VARS (set before starting your app):
 *   OTEL_SERVICE_NAME             - e.g. "checkout-api"
 *   OTEL_EXPORTER_OTLP_ENDPOINT  - e.g. "http://localhost:4317"
 *
 * VERIFY IT'S WORKING:
 *   docker logs otel-collector --tail 30
 *   Query Prometheus: http://localhost:9090
 *   All metrics are prefixed with "otel_" by the Collector's prometheus exporter.
 *
 * INSTRUMENT TYPES — pick the right one:
 *
 *   Counter              Always goes up. Request counts, bytes sent, errors.
 *   UpDownCounter        Goes up and down. Active connections, queue depth, cache size.
 *   Histogram            Measures distributions. Latency, payload size, retry count.
 *   ObservableGauge      Snapshot of a value you poll. Memory usage, CPU %, config values.
 *   ObservableCounter    Monotonically increasing value you poll. Total GC collections, process uptime.
 *   ObservableUpDownCounter  Up/down value you poll. Thread pool size, open file handles.
 */

"use strict";

const { metrics } = require("@opentelemetry/api");

// One meter per logical component. The name becomes the instrumentation scope.
const meter = metrics.getMeter("com.example.checkout", "1.0.0");

// ─── Counter ──────────────────────────────────────────────────────────────────
// Use when: a value only ever increases and you want to track the rate of change.
// Prometheus query: rate(otel_orders_total[5m])
const ordersCounter = meter.createCounter("orders", {
  description: "Total orders submitted",
  unit: "1",
});

// Record with attributes — each unique attribute set is a separate Prometheus series.
// Keep attribute cardinality low: status and region are fine; user_id is not.
function recordOrder(status, region) {
  ordersCounter.add(1, { "order.status": status, "deployment.region": region });
}

// ─── UpDownCounter ────────────────────────────────────────────────────────────
// Use when: a value goes both up and down and you need the current level.
// Prometheus query: otel_active_checkouts
const activeCheckouts = meter.createUpDownCounter("active_checkouts", {
  description: "Number of checkout sessions currently in progress",
  unit: "1",
});

function startCheckout() { activeCheckouts.add(1); }
function endCheckout()   { activeCheckouts.add(-1); }

// ─── Histogram ────────────────────────────────────────────────────────────────
// Use when: you need latency percentiles, not just averages.
// Prometheus query: histogram_quantile(0.95, rate(otel_checkout_duration_milliseconds_bucket[5m]))
const checkoutDuration = meter.createHistogram("checkout.duration", {
  description: "End-to-end checkout latency in milliseconds",
  unit: "ms",
  // advice.explicitBucketBoundaries: define buckets tuned to your SLO thresholds.
  // Default buckets (0, 5, 10, 25 ... 10000ms) are often too coarse for HTTP latency.
  advice: {
    explicitBucketBoundaries: [10, 25, 50, 100, 200, 400, 800, 1600, 3200],
  },
});

async function timedCheckout(checkoutFn, method) {
  const start = performance.now();
  try {
    const result = await checkoutFn();
    checkoutDuration.record(performance.now() - start, {
      "payment.method": method,
      "checkout.status": "success",
    });
    return result;
  } catch (err) {
    checkoutDuration.record(performance.now() - start, {
      "payment.method": method,
      "checkout.status": "error",
    });
    throw err;
  }
}

// ─── ObservableGauge ──────────────────────────────────────────────────────────
// Use when: you want to report the current value of something at scrape time.
// The callback is called when the Collector reads metrics — don't do expensive work here.
// Prometheus query: otel_process_heap_used_bytes
meter.createObservableGauge("process.heap.used", {
  description: "V8 heap used in bytes",
  unit: "By",
}).addCallback((observableResult) => {
  const mem = process.memoryUsage();
  // Each observe() call with different attributes creates a separate time series.
  observableResult.observe(mem.heapUsed,  { "heap.space": "used" });
  observableResult.observe(mem.heapTotal, { "heap.space": "total" });
  observableResult.observe(mem.rss,       { "heap.space": "rss" });
});

// ─── ObservableCounter ────────────────────────────────────────────────────────
// Use when: the raw value is monotonically increasing but you read it by polling
// (e.g., from a system API, cache, or external source) rather than incrementing inline.
// Prometheus converts the reported absolute values to a rate automatically.
// Prometheus query: rate(otel_http_requests_handled_total[5m])
let _totalRequestsHandled = 0; // imagine this is updated by your request handler
meter.createObservableCounter("http.requests.handled", {
  description: "Total HTTP requests handled since process start",
  unit: "1",
}).addCallback((observableResult) => {
  observableResult.observe(_totalRequestsHandled);
});

// ─── ObservableUpDownCounter ──────────────────────────────────────────────────
// Use when: a pool or queue size is maintained externally and you poll it.
// Prometheus query: otel_db_pool_connections
meter.createObservableUpDownCounter("db.pool.connections", {
  description: "Database connection pool state",
  unit: "1",
}).addCallback((observableResult) => {
  // Replace getPoolStats() with your actual pool library's API.
  const stats = getPoolStats();
  observableResult.observe(stats.active, { "pool.state": "active" });
  observableResult.observe(stats.idle,   { "pool.state": "idle" });
  observableResult.observe(stats.waiting,{ "pool.state": "waiting" });
});

// ─── BatchObserver — observe multiple instruments in one callback ──────────────
// Efficient when you read from a single source (e.g. one syscall) to populate
// multiple instruments. All observations share the same timestamp.
const heapGauge   = meter.createObservableGauge("v8.heap.space.used",  { unit: "By" });
const externalGauge = meter.createObservableGauge("v8.external.memory", { unit: "By" });

meter.addBatchObservableCallback(
  (observableResult) => {
    const mem = process.memoryUsage();
    observableResult.observe(heapGauge,    mem.heapUsed);
    observableResult.observe(externalGauge, mem.external);
  },
  [heapGauge, externalGauge],
);

// ─── Placeholder helpers (replace with your real implementations) ──────────────
function getPoolStats() { return { active: 5, idle: 3, waiting: 0 }; }

module.exports = { recordOrder, startCheckout, endCheckout, timedCheckout };
