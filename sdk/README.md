# sdk/ — SDK bootstrap snippets

These are not packages. They are the 20-50 lines you paste into your app's entry point to start emitting OTLP telemetry to the Collector.

---

## Auto vs manual instrumentation

| Language | Auto-instrumentation | Manual SDK |
|----------|---------------------|-----------|
| Node.js | `sdk/node/auto.js` — zero-config, uses `@opentelemetry/auto-instrumentations-node` | `sdk/node/manual.js` — add custom spans and attributes |
| Python | `sdk/python/auto.py` — zero-config, uses `opentelemetry-distro` | `sdk/python/manual.py` — custom spans and metrics |
| Java | `sdk/java/auto.md` — zero-code via `-javaagent` flag, no SDK code | `sdk/java/manual.java` — manual spans when agent isn't available |
| Go | No auto-instrumentation exists (no runtime bytecode injection in Go) | `sdk/go/auto.go` — SDK init + `sdk/go/manual.go` — custom spans |
| .NET | `sdk/dotnet/auto.md` — NuGet auto-instrumentation | `sdk/dotnet/manual.cs` — custom spans via `ActivitySource` |

---

## The two env vars you must set

Every bootstrap file in this directory requires these two env vars:

```bash
export OTEL_SERVICE_NAME=my-service-name
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

`OTEL_SERVICE_NAME` is the most important one — it's what identifies your service in every dashboard, trace, and alert.

`OTEL_EXPORTER_OTLP_ENDPOINT` points at the Collector. `http://localhost:4317` works for local development. For Kubernetes: `http://otel-collector.otel-system.svc.cluster.local:4317`.

---

## Verifying it works

After starting your instrumented app, make one request and then check the Collector's stdout:

```bash
docker logs otel-collector --tail 30
```

You should see `ResourceSpans` or `ScopeSpans` records with your service name and HTTP route spans. If the output is empty, the app isn't sending — check the endpoint and that the bootstrap loaded before other imports.
