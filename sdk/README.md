# sdk/ — SDK bootstrap snippets

These are not packages. They are the 20-50 lines you paste into your app's entry point to start emitting OTLP telemetry to the Collector.

---

## Auto vs manual instrumentation

| Language | Auto-instrumentation | Manual spans | Custom metrics |
|----------|---------------------|--------------|----------------|
| Node.js | `sdk/node/auto.js` — zero-config, uses `@opentelemetry/auto-instrumentations-node` | `sdk/node/manual.js` — custom spans and attributes | `sdk/node/metrics.js` |
| Python | `sdk/python/auto.py` — zero-config, uses `opentelemetry-distro` | `sdk/python/manual.py` — custom spans | `sdk/python/metrics.py` |
| Java | `sdk/java/auto.md` — zero-code via `-javaagent` flag, no SDK code | `sdk/java/manual.java` — manual spans when agent isn't available | `sdk/java/metrics.java` |
| Go | No auto-instrumentation exists (no runtime bytecode injection in Go) | `sdk/go/auto.go` SDK init + `sdk/go/manual.go` custom spans | `sdk/go/metrics.go` |
| .NET | `sdk/dotnet/auto.md` — NuGet auto-instrumentation | `sdk/dotnet/manual.cs` — custom spans via `ActivitySource` | `sdk/dotnet/metrics.cs` |

---

## Custom metrics — all six instrument types

Every `metrics.<ext>` file covers the full set of OpenTelemetry instrument types.
Copy the section that matches your use case.

| Instrument | Direction | When to use |
|------------|-----------|-------------|
| **Counter** | ↑ only | Things that accumulate: requests, errors, bytes sent. Track with `rate()`. |
| **UpDownCounter** | ↑ ↓ | Current level of something: active sessions, queue depth, cache size. |
| **Histogram** | distribution | Latency, payload size, retry counts. Gives you p50/p95/p99. |
| **ObservableGauge** | snapshot, polled | Memory usage, CPU %, config flag values. Callback runs at export time. |
| **ObservableCounter** | ↑ only, polled | Counters owned by an external library (JVM GC, OS metrics). |
| **ObservableUpDownCounter** | ↑ ↓, polled | Pool sizes, thread counts maintained by external code. |

Key rules:
- **Counters are rates** — always query them with `rate(metric[5m])` in Prometheus, never as raw values.
- **Histogram buckets matter** — tune `explicitBucketBoundaries` to your SLO thresholds; the defaults are usually too coarse.
- **Keep attribute cardinality low** — adding `user_id` or `request_id` as attributes creates millions of Prometheus series and will OOM your Prometheus.
- **Create instruments once, reuse everywhere** — instrument creation is expensive. Store them in module-level variables or a singleton struct.
- **Observable callbacks must be fast** — they run on the SDK's export goroutine/thread. No blocking I/O, no locks held for more than microseconds.

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
