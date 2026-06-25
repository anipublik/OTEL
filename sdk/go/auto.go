// Go auto-instrumentation entry point.
// "Auto" in Go means: SDK initialization + HTTP middleware.
// Go has no runtime bytecode injection, so there's no truly zero-code path.
// This file is as close as Go gets: ~40 lines to a fully instrumented HTTP server.
//
// REQUIRED ENV VARS:
//   OTEL_SERVICE_NAME             - your service name (e.g. "api-gateway")
//   OTEL_EXPORTER_OTLP_ENDPOINT  - Collector address (e.g. "localhost:4317") — no http:// for gRPC
//
// VERIFY IT'S WORKING:
//   docker logs otel-collector --tail 30
//   Look for ScopeSpans with your service name and http.request.method.

package telemetry

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// Init initializes the global TracerProvider and MeterProvider.
// Returns a shutdown function — call it with a timeout context on process exit.
func Init(ctx context.Context) (func(context.Context) error, error) {
	serviceName := os.Getenv("OTEL_SERVICE_NAME")
	if serviceName == "" {
		serviceName = "unknown-go-service"
	}
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "localhost:4317"
	}

	res, err := resource.New(ctx,
		resource.WithFromEnv(),
		resource.WithProcess(),
		resource.WithOS(),
		resource.WithHost(),
		resource.WithAttributes(semconv.ServiceName(serviceName)),
	)
	if err != nil {
		return nil, fmt.Errorf("building resource: %w", err)
	}

	conn, err := grpc.DialContext(ctx, endpoint,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
	)
	if err != nil {
		return nil, fmt.Errorf("connecting to Collector at %s: %w", endpoint, err)
	}

	traceExporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithGRPCConn(conn))
	if err != nil {
		return nil, fmt.Errorf("creating trace exporter: %w", err)
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithBatcher(traceExporter),
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	metricExporter, err := otlpmetricgrpc.New(ctx, otlpmetricgrpc.WithGRPCConn(conn))
	if err != nil {
		return nil, fmt.Errorf("creating metric exporter: %w", err)
	}
	mp := metric.NewMeterProvider(
		metric.WithResource(res),
		metric.WithReader(metric.NewPeriodicReader(metricExporter,
			metric.WithInterval(30*time.Second),
		)),
	)
	otel.SetMeterProvider(mp)

	return func(ctx context.Context) error {
		_ = tp.Shutdown(ctx)
		_ = mp.Shutdown(ctx)
		return conn.Close()
	}, nil
}

// NewHandler wraps an http.Handler with OTel instrumentation.
// Every request gets a span with http.request.method, http.route, http.response.status_code.
//
// Usage:
//   mux := http.NewServeMux()
//   mux.HandleFunc("/api/orders", handleOrders)
//   http.ListenAndServe(":8080", telemetry.NewHandler(mux, "server"))
func NewHandler(handler http.Handler, serverName string) http.Handler {
	return otelhttp.NewHandler(handler, serverName)
}
