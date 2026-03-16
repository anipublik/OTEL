// Go custom metrics template — all six OpenTelemetry instrument types.
//
// SETUP: call telemetry.Init() from auto.go in main() first.
// It registers the global MeterProvider. All instrument creation below
// uses otel.Meter() which reads from that global provider.
//
// REQUIRED ENV VARS:
//   OTEL_SERVICE_NAME             - e.g. "checkout-api"
//   OTEL_EXPORTER_OTLP_ENDPOINT  - e.g. "localhost:4317" (no scheme for gRPC)
//
// VERIFY IT'S WORKING:
//   docker logs otel-collector --tail 30
//   Query Prometheus: http://localhost:9090
//   All metrics are prefixed with "otel_" by the Collector's prometheus exporter.
//
// INSTRUMENT TYPES — pick the right one:
//
//   Int64Counter                  Always goes up. Request counts, bytes sent, errors.
//   Int64UpDownCounter            Goes up and down. Active connections, queue depth.
//   Int64Histogram                Measures distributions. Latency, payload size.
//   Int64ObservableGauge          Snapshot value polled at collection time. Memory, CPU%.
//   Int64ObservableCounter        Monotonically increasing value polled externally.
//   Int64ObservableUpDownCounter  Up/down value polled externally. Thread pool size.
//
// Float64 variants exist for all types above — use them for values like CPU percentage.

package telemetry

import (
	"context"
	"runtime"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// AppMetrics holds all instruments for a component.
// Create one per logical component; store it as a struct field or package-level var.
type AppMetrics struct {
	// ─── Counter ──────────────────────────────────────────────────────────────
	// Use when: a value only ever increases and you want rate of change.
	// Prometheus query: rate(otel_orders_total[5m])
	ordersCounter metric.Int64Counter

	// ─── UpDownCounter ────────────────────────────────────────────────────────
	// Use when: a value goes both up and down and you need the current level.
	// Prometheus query: otel_active_checkouts
	activeCheckouts metric.Int64UpDownCounter

	// ─── Histogram ────────────────────────────────────────────────────────────
	// Use when: you need p50/p95/p99, not just averages.
	// Prometheus query: histogram_quantile(0.95, rate(otel_checkout_duration_ms_bucket[5m]))
	checkoutDuration metric.Int64Histogram
}

// NewAppMetrics creates all instruments. Call once at startup and reuse — instruments
// are safe for concurrent use and expensive to create.
func NewAppMetrics() (*AppMetrics, error) {
	// One meter per component. Name becomes the instrumentation scope in Grafana/Tempo.
	m := otel.Meter("com.example.checkout", metric.WithInstrumentationVersion("1.0.0"))

	ordersCounter, err := m.Int64Counter(
		"orders",
		metric.WithDescription("Total orders submitted"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, err
	}

	activeCheckouts, err := m.Int64UpDownCounter(
		"active_checkouts",
		metric.WithDescription("Number of checkout sessions currently in progress"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return nil, err
	}

	checkoutDuration, err := m.Int64Histogram(
		"checkout.duration",
		metric.WithDescription("End-to-end checkout latency in milliseconds"),
		metric.WithUnit("ms"),
		// ExplicitBucketBoundaries: tune buckets to your SLO thresholds.
		// Default buckets are often too coarse for HTTP latency work.
		metric.WithExplicitBucketBoundaries(10, 25, 50, 100, 200, 400, 800, 1600, 3200),
	)
	if err != nil {
		return nil, err
	}

	return &AppMetrics{
		ordersCounter:    ordersCounter,
		activeCheckouts:  activeCheckouts,
		checkoutDuration: checkoutDuration,
	}, nil
}

func (a *AppMetrics) RecordOrder(ctx context.Context, status, region string) {
	// Attributes are the Prometheus label dimensions.
	// Keep cardinality low: "status" and "region" are fine; "userID" is not.
	a.ordersCounter.Add(ctx, 1,
		metric.WithAttributes(
			attribute.String("order.status",      status),
			attribute.String("deployment.region", region),
		),
	)
}

func (a *AppMetrics) StartCheckout(ctx context.Context) { a.activeCheckouts.Add(ctx, 1) }
func (a *AppMetrics) EndCheckout(ctx context.Context)   { a.activeCheckouts.Add(ctx, -1) }

func (a *AppMetrics) TimedCheckout(ctx context.Context, method string, fn func() error) error {
	start := time.Now()
	err := fn()
	elapsed := time.Since(start).Milliseconds()
	status := "success"
	if err != nil {
		status = "error"
	}
	a.checkoutDuration.Record(ctx, elapsed,
		metric.WithAttributes(
			attribute.String("payment.method",  method),
			attribute.String("checkout.status", status),
		),
	)
	return err
}

// ─── Observable instruments — registered at startup, polled at collection time ──
//
// These are package-level because they hold internal SDK state.
// Register them once during Init; the SDK calls the callbacks on each export cycle.

// RegisterRuntimeMetrics registers observable instruments that poll the Go runtime.
// Call this once after Init() has set up the global MeterProvider.
func RegisterRuntimeMetrics() error {
	m := otel.Meter("com.example.runtime")

	// ─── ObservableGauge ────────────────────────────────────────────────────
	// Use when: you want a current snapshot at collection time.
	// The callback is called by the SDK — keep it fast (no network I/O).
	// Prometheus query: otel_go_goroutines
	_, err := m.Int64ObservableGauge(
		"go.goroutines",
		metric.WithDescription("Number of live goroutines"),
		metric.WithUnit("1"),
		metric.WithInt64Callback(func(_ context.Context, o metric.Int64Observer) error {
			o.Observe(int64(runtime.NumGoroutine()))
			return nil
		}),
	)
	if err != nil {
		return err
	}

	// ─── ObservableGauge (multiple attributes, single callback) ─────────────
	// Prometheus query: otel_go_memory_bytes{memory_type="heap_alloc"}
	_, err = m.Int64ObservableGauge(
		"go.memory",
		metric.WithDescription("Go runtime memory statistics"),
		metric.WithUnit("By"),
		metric.WithInt64Callback(func(_ context.Context, o metric.Int64Observer) error {
			var ms runtime.MemStats
			runtime.ReadMemStats(&ms)
			o.Observe(int64(ms.HeapAlloc),    attribute.String("memory.type", "heap_alloc"))
			o.Observe(int64(ms.HeapSys),      attribute.String("memory.type", "heap_sys"))
			o.Observe(int64(ms.StackInuse),   attribute.String("memory.type", "stack_inuse"))
			o.Observe(int64(ms.MSpanInuse),   attribute.String("memory.type", "mspan_inuse"))
			return nil
		}),
	)
	if err != nil {
		return err
	}

	// ─── ObservableCounter ──────────────────────────────────────────────────
	// Use when: a counter is maintained in a library or external system and
	// you can only read its absolute value, not call Add() inline.
	// The SDK converts successive absolute readings into a rate automatically.
	// Prometheus query: rate(otel_go_gc_runs_total[5m])
	_, err = m.Int64ObservableCounter(
		"go.gc.runs",
		metric.WithDescription("Total number of completed GC cycles since process start"),
		metric.WithUnit("1"),
		metric.WithInt64Callback(func(_ context.Context, o metric.Int64Observer) error {
			var ms runtime.MemStats
			runtime.ReadMemStats(&ms)
			o.Observe(int64(ms.NumGC))
			return nil
		}),
	)
	if err != nil {
		return err
	}

	// ─── ObservableUpDownCounter ────────────────────────────────────────────
	// Use when: a pool or queue size is managed by a library you don't control
	// and you can only poll its current size.
	// Prometheus query: otel_db_pool_connections
	_, err = m.Int64ObservableUpDownCounter(
		"db.pool.connections",
		metric.WithDescription("Database connection pool state"),
		metric.WithUnit("1"),
		metric.WithInt64Callback(func(_ context.Context, o metric.Int64Observer) error {
			// Replace with your real pool library's stats call.
			stats := getPoolStats()
			o.Observe(int64(stats.active),  attribute.String("pool.state", "active"))
			o.Observe(int64(stats.idle),    attribute.String("pool.state", "idle"))
			o.Observe(int64(stats.waiting), attribute.String("pool.state", "waiting"))
			return nil
		}),
	)
	return err
}

// ─── Batch observable — single registration, multiple instruments ─────────────
// Use when: you read from one source (e.g., one syscall) to fill multiple instruments.
// This avoids the overhead of N separate callbacks calling the same expensive API.
func RegisterBatchRuntimeMetrics() error {
	m := otel.Meter("com.example.runtime.batch")

	gcPause, err := m.Int64ObservableGauge("go.gc.pause_ns",
		metric.WithDescription("Most recent GC pause duration in nanoseconds"),
		metric.WithUnit("ns"),
	)
	if err != nil {
		return err
	}

	gcCount, err := m.Int64ObservableCounter("go.gc.count",
		metric.WithDescription("Total GC cycles"),
		metric.WithUnit("1"),
	)
	if err != nil {
		return err
	}

	// One callback reads MemStats once and populates both instruments.
	_, err = m.RegisterCallback(
		func(_ context.Context, o metric.Observer) error {
			var ms runtime.MemStats
			runtime.ReadMemStats(&ms)
			o.ObserveInt64(gcPause, int64(ms.PauseNs[(ms.NumGC+255)%256]))
			o.ObserveInt64(gcCount, int64(ms.NumGC))
			return nil
		},
		gcPause, gcCount,
	)
	return err
}

type poolStats struct{ active, idle, waiting int }
func getPoolStats() poolStats { return poolStats{active: 5, idle: 3, waiting: 0} }
