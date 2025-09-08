# .NET auto-instrumentation — zero-code setup via NuGet launcher

.NET auto-instrumentation uses the `dotnet-otel` global tool to inject instrumentation at process startup without code changes.

---

## Step 1 — Install the global tool

```bash
dotnet tool install --global dotnet-otel
dotnet-otel install
```

`dotnet-otel install` downloads the native profiler and sets up the environment. Run it once per machine.

## Step 2 — Set env vars

```bash
export OTEL_SERVICE_NAME=my-service
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
```

## Step 3 — Run your app through the launcher

```bash
dotnet-otel run -- dotnet run
# or:
dotnet-otel run -- dotnet MyApp.dll
```

The launcher sets up the CLR profiler environment variables, then executes your app.

---

## Docker usage

```dockerfile
FROM mcr.microsoft.com/dotnet/aspnet:8.0

WORKDIR /app
COPY publish/ .

# Install the auto-instrumentation before switching to the app user.
RUN dotnet tool install --global dotnet-otel && \
    /root/.dotnet/tools/dotnet-otel install

ENV PATH="${PATH}:/root/.dotnet/tools"

# Set OTEL env vars — override with Docker -e flags or K8s env.
ENV OTEL_SERVICE_NAME=my-dotnet-service
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

ENTRYPOINT ["dotnet-otel", "run", "--", "dotnet", "MyApp.dll"]
```

---

## What gets instrumented automatically

- ASP.NET Core — inbound HTTP request spans (route, method, status code)
- HttpClient / HttpClientFactory — outbound HTTP spans
- SqlClient — database query spans (SQL text sanitized by default)
- gRPC (Grpc.Net.Client)
- Entity Framework Core (with `OpenTelemetry.Instrumentation.EntityFrameworkCore` NuGet)
- StackExchange.Redis (with `OpenTelemetry.Instrumentation.StackExchangeRedis` NuGet)

The full list: [opentelemetry.io/docs/zero-code/net/](https://opentelemetry.io/docs/zero-code/net/)

---

## Alternative: NuGet SDK (code-based)

If you prefer to configure OTEL in code (more control), see `sdk/dotnet/manual.cs` for the `AddOpenTelemetry()` setup using the `OpenTelemetry.Extensions.Hosting` NuGet package.

---

## Verify it's working

Make one HTTP request to your ASP.NET Core endpoint, then:

```bash
docker logs otel-collector --tail 30
```

Look for `ScopeSpans` with `http.request.method` and `http.route` attributes.
