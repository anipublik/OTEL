// .NET custom metrics template — all six OpenTelemetry instrument types.
//
// .NET's native instrumentation model uses System.Diagnostics.Metrics.Meter.
// OpenTelemetry's SDK bridges these to OTLP automatically — you define instruments
// using .NET's built-in API and the SDK handles export.
//
// SETUP: Register your Meter name in Program.cs:
//   builder.Services.AddOpenTelemetry()
//     .WithMetrics(metrics => metrics
//       .AddMeter("MyCompany.Checkout")    // ← matches the Meter name below
//       .AddOtlpExporter());
//
// REQUIRED ENV VARS:
//   OTEL_SERVICE_NAME             - e.g. "checkout-api"
//   OTEL_EXPORTER_OTLP_ENDPOINT  - e.g. "http://localhost:4317"
//
// VERIFY IT'S WORKING:
//   docker logs otel-collector --tail 30
//   Query Prometheus: http://localhost:9090
//   All metrics are prefixed with "otel_" by the Collector's prometheus exporter.
//
// INSTRUMENT TYPES — pick the right one:
//
//   Counter<T>                  Always goes up. Request counts, bytes sent, errors.
//   UpDownCounter<T>            Goes up and down. Active connections, queue depth.
//   Histogram<T>                Measures distributions. Latency, payload size.
//   ObservableGauge<T>          Snapshot of a value polled at collection time. Memory, CPU%.
//   ObservableCounter<T>        Monotonically increasing value polled externally.
//   ObservableUpDownCounter<T>  Up/down value polled externally. Thread pool size.

using System.Diagnostics.Metrics;
using System.Runtime.InteropServices;
using Microsoft.Extensions.DependencyInjection;

namespace MyApp.Telemetry;

/// <summary>
/// Centralized metrics for the Checkout domain.
/// Register as a singleton in DI; all instruments are thread-safe.
/// </summary>
public sealed class CheckoutMetrics : IDisposable
{
    // Meter: the .NET equivalent of OpenTelemetry's Meter.
    // One per logical component. Name must be registered with .AddMeter() in Program.cs.
    private readonly Meter _meter;

    // ─── Counter ──────────────────────────────────────────────────────────────
    // Use when: a value only ever increases and you want the rate of change.
    // Prometheus query: rate(otel_orders_total[5m])
    private readonly Counter<long> _ordersCounter;

    // ─── UpDownCounter ────────────────────────────────────────────────────────
    // Use when: a value goes both up and down and you need the current level.
    // Prometheus query: otel_active_checkouts
    private readonly UpDownCounter<long> _activeCheckouts;

    // ─── Histogram ────────────────────────────────────────────────────────────
    // Use when: you need p50/p95/p99 latency percentiles, not just averages.
    // Prometheus query: histogram_quantile(0.95, rate(otel_checkout_duration_ms_bucket[5m]))
    private readonly Histogram<double> _checkoutDuration;

    // ─── ObservableGauge ──────────────────────────────────────────────────────
    // Use when: you want to report the current value at collection time.
    // The callback is called by the SDK on each export cycle — keep it fast.
    // Prometheus query: otel_process_working_set_bytes
    private readonly ObservableGauge<long> _memoryGauge;

    // ─── ObservableCounter ────────────────────────────────────────────────────
    // Use when: a counter is maintained externally and you can only poll its value.
    // The SDK converts successive absolute readings into a rate automatically.
    // Prometheus query: rate(otel_process_gc_collections_total[5m])
    private readonly ObservableCounter<long> _gcCounter;

    // ─── ObservableUpDownCounter ──────────────────────────────────────────────
    // Use when: a pool or queue size is managed externally and you poll it.
    // Prometheus query: otel_thread_pool_threads
    private readonly ObservableUpDownCounter<long> _threadPoolGauge;

    public CheckoutMetrics()
    {
        _meter = new Meter("MyCompany.Checkout", "1.0.0");

        _ordersCounter = _meter.CreateCounter<long>(
            "orders",
            unit: "1",
            description: "Total orders submitted"
        );

        _activeCheckouts = _meter.CreateUpDownCounter<long>(
            "active_checkouts",
            unit: "1",
            description: "Number of checkout sessions currently in progress"
        );

        _checkoutDuration = _meter.CreateHistogram<double>(
            "checkout.duration",
            unit: "ms",
            description: "End-to-end checkout latency in milliseconds"
        );

        // Callbacks for observable instruments — return IEnumerable<Measurement<T>>.
        _memoryGauge = _meter.CreateObservableGauge<long>(
            "process.memory.working_set",
            observeValues: ObserveMemory,
            unit: "By",
            description: "Process working set memory in bytes"
        );

        _gcCounter = _meter.CreateObservableCounter<long>(
            "process.gc.collections",
            observeValues: ObserveGcCollections,
            unit: "1",
            description: "Total GC collections since process start, by generation"
        );

        _threadPoolGauge = _meter.CreateObservableUpDownCounter<long>(
            "thread_pool.threads",
            observeValues: ObserveThreadPool,
            unit: "1",
            description: "Thread pool thread counts by state"
        );
    }

    // ─── Synchronous instrument usage ─────────────────────────────────────────

    public void RecordOrder(string status, string region)
    {
        // TagList: the Prometheus label dimensions for this metric.
        // Keep cardinality low: "status" and "region" are fine; "userId" is not.
        _ordersCounter.Add(1, new TagList
        {
            { "order.status",      status },
            { "deployment.region", region },
        });
    }

    public void StartCheckout() => _activeCheckouts.Add(1);
    public void EndCheckout()   => _activeCheckouts.Add(-1);

    public async Task<T> TimedCheckoutAsync<T>(Func<Task<T>> fn, string method)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            var result = await fn();
            sw.Stop();
            _checkoutDuration.Record(sw.Elapsed.TotalMilliseconds, new TagList
            {
                { "payment.method",  method },
                { "checkout.status", "success" },
            });
            return result;
        }
        catch
        {
            sw.Stop();
            _checkoutDuration.Record(sw.Elapsed.TotalMilliseconds, new TagList
            {
                { "payment.method",  method },
                { "checkout.status", "error" },
            });
            throw;
        }
    }

    // ─── Observable instrument callbacks ──────────────────────────────────────
    // Each method returns multiple Measurement<T> to report per-attribute values.

    private static IEnumerable<Measurement<long>> ObserveMemory()
    {
        using var proc = System.Diagnostics.Process.GetCurrentProcess();
        yield return new Measurement<long>(proc.WorkingSet64,
            new KeyValuePair<string, object?>("memory.type", "working_set"));
        yield return new Measurement<long>(proc.PrivateMemorySize64,
            new KeyValuePair<string, object?>("memory.type", "private"));
    }

    private static IEnumerable<Measurement<long>> ObserveGcCollections()
    {
        // Absolute values only — the SDK converts to a rate (counter_total in Prometheus).
        for (int gen = 0; gen <= GC.MaxGeneration; gen++)
        {
            yield return new Measurement<long>(GC.CollectionCount(gen),
                new KeyValuePair<string, object?>("gc.generation", gen.ToString()));
        }
    }

    private static IEnumerable<Measurement<long>> ObserveThreadPool()
    {
        ThreadPool.GetAvailableThreads(out int availWorker, out int availIo);
        ThreadPool.GetMaxThreads(out int maxWorker, out int maxIo);
        yield return new Measurement<long>(maxWorker - availWorker,
            new KeyValuePair<string, object?>("thread_pool.type", "worker_active"));
        yield return new Measurement<long>(availWorker,
            new KeyValuePair<string, object?>("thread_pool.type", "worker_idle"));
        yield return new Measurement<long>(maxIo - availIo,
            new KeyValuePair<string, object?>("thread_pool.type", "io_active"));
    }

    public void Dispose() => _meter.Dispose();
}

// ─── DI registration helper ───────────────────────────────────────────────────
// Add this in Program.cs:
//
//   builder.Services.AddSingleton<CheckoutMetrics>();
//   builder.Services.AddOpenTelemetry()
//     .WithMetrics(m => m
//       .AddMeter("MyCompany.Checkout")
//       .AddOtlpExporter());
//
// Inject CheckoutMetrics into your service:
//
//   public class CheckoutService(CheckoutMetrics metrics) { ... }
public static class MetricsDiExtensions
{
    public static IServiceCollection AddCheckoutMetrics(this IServiceCollection services)
        => services.AddSingleton<CheckoutMetrics>();
}
