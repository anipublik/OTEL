# Go — OpenTelemetry setup

Go has no runtime bytecode injection, so there's no zero-code option. You initialize the SDK in `main()` and pass a tracer/meter down through your code. The bootstrap file (`sdk-bootstrap.go`) is ~50 lines and covers the standard setup.

---

## Prerequisites

- Go 1.21+
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Install SDK packages

```bash
go get go.opentelemetry.io/otel@latest
go get go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc@latest
go get go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetricgrpc@latest
go get go.opentelemetry.io/otel/sdk/trace@latest
go get go.opentelemetry.io/otel/sdk/metric@latest
go get go.opentelemetry.io/otel/sdk/resource@latest
go get go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp@latest
go get google.golang.org/grpc@latest
```

## Add the bootstrap to your app

Copy `sdk-bootstrap.go` into your project (e.g. `internal/telemetry/otel.go`) and call `InitOTel` from `main()`:

```go
func main() {
    ctx := context.Background()

    shutdown, err := telemetry.InitOTel(ctx)
    if err != nil {
        log.Fatalf("failed to initialize OTEL: %v", err)
    }
    defer shutdown(ctx)

    // ... start your HTTP server or run your app
}
```

## Set environment variables

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=localhost:4317   # no http:// for gRPC
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
```

Note: Go's gRPC exporter expects `host:port` without a scheme. The HTTP exporter (`otlptracegrpc` with `WithInsecure`) adds the scheme internally.

## Instrument your HTTP handlers

```go
import "go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

// Wrap your mux/router with the OTel HTTP middleware:
handler := otelhttp.NewHandler(mux, "server")
http.ListenAndServe(":8080", handler)
```

## Verify it's working

Make one HTTP request, then:

```bash
docker logs otel-collector --tail 30
```

You should see spans with `http.request.method`, `http.route`, and your service name.

---

## What needs manual instrumentation in Go

Unlike Node.js or Java, Go libraries don't auto-instrument. You add spans explicitly:

```go
tracer := otel.Tracer("com.example.my-component")
ctx, span := tracer.Start(ctx, "process-order")
defer span.End()
```

Popular libraries with built-in or contrib instrumentation:
- `net/http` — `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp`
- `database/sql` — `go.opentelemetry.io/contrib/instrumentation/database/sql/otelsql`
- `google.golang.org/grpc` — `go.opentelemetry.io/contrib/instrumentation/google.golang.org/grpc/otelgrpc`
- `github.com/gin-gonic/gin` — `go.opentelemetry.io/contrib/instrumentation/github.com/gin-gonic/gin/otelgin`
- `github.com/go-chi/chi` — `go.opentelemetry.io/contrib/instrumentation/github.com/go-chi/chi/v5/otelchi`
