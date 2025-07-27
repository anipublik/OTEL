# .NET / ASP.NET Core — OpenTelemetry setup

.NET offers two instrumentation paths: auto-instrumentation via a NuGet-based launcher (zero code changes), and manual SDK setup for custom spans and metrics.

---

## Prerequisites

- .NET 6+
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Option 1 — Auto-instrumentation (zero code changes)

Install the auto-instrumentation package globally:

```bash
dotnet tool install --global dotnet-otel
dotnet-otel install
```

Then run your app with OTEL env vars set:

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0

dotnet-otel run -- dotnet run
```

The launcher injects instrumentation for ASP.NET Core, HttpClient, SqlClient, gRPC, and Entity Framework.

## Option 2 — NuGet SDK (code-based, more control)

Install packages:

```bash
dotnet add package OpenTelemetry.Extensions.Hosting
dotnet add package OpenTelemetry.Instrumentation.AspNetCore
dotnet add package OpenTelemetry.Instrumentation.Http
dotnet add package OpenTelemetry.Exporter.OpenTelemetryProtocol
```

Copy `sdk-bootstrap.cs` and call `AddOpenTelemetry()` in `Program.cs`:

```csharp
using OpenTelemetryBootstrap;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddOpenTelemetryInstrumentation(builder.Configuration);
```

## Set environment variables

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
```

Or in `appsettings.json`:

```json
{
  "OpenTelemetry": {
    "ServiceName": "my-service",
    "Endpoint": "http://localhost:4317"
  }
}
```

## Verify it's working

Make one HTTP request, then:

```bash
docker logs otel-collector --tail 30
```

You should see spans with `http.request.method`, `http.route`, and your service name.

---

## What gets instrumented automatically

- `ASP.NET Core` — inbound HTTP spans with route, method, status code
- `HttpClient` — outbound HTTP spans
- `SqlClient` — database query spans (SQL text optional, off by default for security)
- `gRPC` — if using `Grpc.Net.Client`
- `Entity Framework Core` — query spans (via `OpenTelemetry.Instrumentation.EntityFrameworkCore`)
