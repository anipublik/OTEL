// Go manual instrumentation — custom spans, attributes, and metrics.
//
// Use this AFTER Init() from auto.go has set up the global providers.
// auto.go configures otel.GetTracerProvider() and otel.GetMeterProvider();
// this file shows how to use them for custom business operations.

package telemetry

import (
	"context"
	"fmt"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

// ComponentTracer creates a tracer for a specific component.
// Call this once at package init time and reuse the tracer.
// The component name appears as the instrumentation scope in Tempo.
func ComponentTracer(name string) trace.Tracer {
	return otel.Tracer(name)
}

// ComponentMeter creates a meter for a specific component.
func ComponentMeter(name string) metric.Meter {
	return otel.Meter(name)
}

// Example: wrapping a business operation in a custom span.
// The tracer is typically a package-level variable created at init time.
var orderTracer = otel.Tracer("com.example.orders")

func ProcessOrder(ctx context.Context, orderID string, itemCount int) error {
	// Start a span. The span is linked to the parent span in ctx (if any).
	ctx, span := orderTracer.Start(ctx, "process-order",
		// SpanKindInternal: work happening inside the service, not an inbound/outbound call.
		trace.WithSpanKind(trace.SpanKindInternal),
	)
	defer span.End()

	// Set attributes. Use OTel semantic convention names where they exist.
	// https://opentelemetry.io/docs/specs/semconv/
	span.SetAttributes(
		attribute.String("order.id", orderID),
		attribute.Int("order.item_count", itemCount),
	)

	if err := doWork(ctx, orderID); err != nil {
		// RecordError attaches the error to the span with a stack trace.
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return fmt.Errorf("processing order %s: %w", orderID, err)
	}

	span.SetStatus(codes.Ok, "")
	return nil
}

// Example: adding span events (timestamped annotations on the span timeline).
func processPayment(ctx context.Context, amount float64) error {
	ctx, span := orderTracer.Start(ctx, "process-payment")
	defer span.End()

	// AddEvent: a timestamped log entry attached to this span.
	span.AddEvent("payment-gateway-called", trace.WithAttributes(
		attribute.String("gateway", "stripe"),
		attribute.Float64("amount.usd", amount),
	))

	// ... call payment gateway ...

	span.AddEvent("payment-gateway-response", trace.WithAttributes(
		attribute.String("gateway.status", "success"),
	))

	return nil
}

// Example: custom metrics alongside spans.
var (
	orderMeter    = otel.Meter("com.example.orders")
	// Counters and histograms are safe to use concurrently — create once, reuse.
	ordersCounter, _ = orderMeter.Int64Counter(
		"orders.processed",
		metric.WithDescription("Total orders processed"),
		metric.WithUnit("1"),
	)
	orderDuration, _ = orderMeter.Int64Histogram(
		"order.duration",
		metric.WithDescription("Time to process an order in milliseconds"),
		metric.WithUnit("ms"),
	)
)

func recordOrderMetrics(ctx context.Context, durationMs int64, success bool) {
	status := "success"
	if !success {
		status = "error"
	}

	// Counter: increment by 1 with labels.
	ordersCounter.Add(ctx, 1, metric.WithAttributes(
		attribute.String("status", status),
	))

	// Histogram: record the duration. Labels become Prometheus le buckets.
	orderDuration.Record(ctx, durationMs, metric.WithAttributes(
		attribute.String("status", status),
	))
}

// Example: propagating context across goroutines.
// Always pass ctx through your call stack — never call otel.Tracer().Start() with context.Background()
// inside a request handler, or the span will be disconnected from the trace.
func runAsync(ctx context.Context) {
	go func() {
		// Pass the parent context to preserve the trace chain.
		ctx, span := orderTracer.Start(ctx, "async-work")
		defer span.End()

		_ = doWork(ctx, "async")
		_ = time.Now() // placeholder
	}()
}

func doWork(ctx context.Context, id string) error {
	return nil
}
