// OpenTelemetry SDK bootstrap for Go.
//
// Copy this file into your project (e.g. internal/telemetry/otel.go) and
// call InitOTel from main() before starting your server.
//
// REQUIRED ENV VARS — set before running your app:
//
//   OTEL_SERVICE_NAME              - shown on every span/metric (e.g. "payments-api")
//   OTEL_EXPORTER_OTLP_ENDPOINT   - Collector address WITHOUT scheme (e.g. "localhost:4317")
//                                   For HTTP exporter use full URL: "http://localhost:4318"
//
// OPTIONAL ENV VARS:
//
//   OTEL_RESOURCE_ATTRIBUTES      - "deployment.environment=local,service.version=1.0.0"
//
// VERIFY IT'S WORKING:
//   Make one request to your service, then:
//     docker logs otel-collector --tail 30
//   Look for ScopeSpans with your service name and HTTP route.

package telemetry

import (
	"context"
	"fmt"
	"os"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// InitOTel initializes the OpenTelemetry SDK and returns a shutdown function.
// Call the returned shutdown function with a timeout context on process exit.
//
//	shutdown, err := telemetry.InitOTel(ctx)
//	if err != nil { log.Fatal(err) }
//	defer shutdown(ctx)
func InitOTel(ctx context.Context) (func(context.Context) error, error) {
	serviceName := os.Getenv("OTEL_SERVICE_NAME")
	if serviceName == "" {
		serviceName = "unknown-go-service"
	}
	// Go's gRPC exporter expects "host:port" without a scheme.
	// If OTEL_EXPORTER_OTLP_ENDPOINT is set by the SDK environment variable,
	// the exporters below will pick it up automatically — no need to read it here.
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "localhost:4317"
	}

	// Resource: attributes attached to every signal this process emits.
	res, err := resource.New(ctx,
		resource.WithFromEnv(),                        // reads OTEL_RESOURCE_ATTRIBUTES
		resource.WithProcess(),                        // adds process.pid, process.executable
		resource.WithOS(),                             // adds os.type, os.description
		resource.WithHost(),                           // adds host.name
		resource.WithAttributes(
			semconv.ServiceName(serviceName),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("building resource: %w", err)
	}

	// gRPC connection shared by both trace and metric exporters.
	// WithInsecure: no TLS. TODO: add TLS for production — see infra/docker/README.md.
	conn, err := grpc.DialContext(ctx, endpoint,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, fmt.Errorf("connecting to Collector at %s: %w", endpoint, err)
	}

	// --- Traces ---
	traceExporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithGRPCConn(conn))
	if err != nil {
		return nil, fmt.Errorf("creating trace exporter: %w", err)
	}

	tracerProvider := sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		// BatchSpanProcessor: buffers and exports in bulk.
		// AlwaysSample: export every span. In production, switch to a tail sampler.
		sdktrace.WithBatcher(traceExporter,
			sdktrace.WithMaxExportBatchSize(512),
			sdktrace.WithBatchTimeout(5*time.Second),
		),
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)

	// Set the global tracer provider so otel.Tracer() works anywhere in the app.
	otel.SetTracerProvider(tracerProvider)

	// W3C TraceContext + Baggage propagation.
	// This lets spans be linked across service boundaries via HTTP headers.
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	// --- Metrics ---
	metricExporter, err := otlpmetricgrpc.New(ctx, otlpmetricgrpc.WithGRPCConn(conn))
	if err != nil {
		return nil, fmt.Errorf("creating metric exporter: %w", err)
	}

	meterProvider := metric.NewMeterProvider(
		metric.WithResource(res),
		// PeriodicReader: pushes metrics every 30s.
		// Lower the interval if you need sub-minute alerting resolution.
		metric.WithReader(metric.NewPeriodicReader(metricExporter,
			metric.WithInterval(30*time.Second),
		)),
	)

	otel.SetMeterProvider(meterProvider)

	// Shutdown flushes pending spans and metrics before the process exits.
	return func(ctx context.Context) error {
		if err := tracerProvider.Shutdown(ctx); err != nil {
			return fmt.Errorf("shutting down tracer provider: %w", err)
		}
		if err := meterProvider.Shutdown(ctx); err != nil {
			return fmt.Errorf("shutting down meter provider: %w", err)
		}
		return conn.Close()
	}, nil
}
