// .NET manual instrumentation — custom spans and metrics using ActivitySource and Meter.
//
// .NET's native instrumentation model uses System.Diagnostics.ActivitySource (spans)
// and System.Diagnostics.Metrics.Meter (metrics). OpenTelemetry's SDK bridges these
// to OTLP automatically — you don't need to reference OTEL APIs directly in business code.
//
// SETUP: Call AddOpenTelemetryInstrumentation() in Program.cs (from sdk-bootstrap.cs
// in configs/dotnet/), then use ActivitySource and Meter anywhere in your code.
//
// VERIFY IT'S WORKING:
//   Run your app, make one request, then: docker logs otel-collector --tail 30
//   Look for ScopeSpans with "MyCompany.Orders" as the instrumentation scope.

using System.Diagnostics;
using System.Diagnostics.Metrics;

namespace MyApp.Telemetry;

/// <summary>
/// Centralized telemetry for the Orders domain.
/// Create ActivitySource and Meter as static singletons — they're thread-safe.
/// </summary>
public static class OrdersTelemetry
{
    // ActivitySource: the .NET equivalent of OpenTelemetry's Tracer.
    // The name appears as the instrumentation scope in Tempo.
    // Register this source name in AddOpenTelemetryInstrumentation() with .AddSource("MyCompany.Orders").
    public static readonly ActivitySource Source = new("MyCompany.Orders", "1.0.0");

    // Meter: the .NET equivalent of OpenTelemetry's Meter.
    // Register this meter name with .AddMeter("MyCompany.Orders") in the SDK setup.
    private static readonly Meter Meter = new("MyCompany.Orders", "1.0.0");

    // Counter: use for "how many times did X happen" questions.
    public static readonly Counter<long> OrdersProcessed = Meter.CreateCounter<long>(
        "orders.processed",
        unit: "1",
        description: "Total orders successfully processed"
    );

    // Histogram: use for distributions like latency, payload size, queue depth.
    public static readonly Histogram<double> OrderDuration = Meter.CreateHistogram<double>(
        "order.duration",
        unit: "ms",
        description: "Time to process an order in milliseconds"
    );
}

/// <summary>
/// Example: wrapping a business operation in a custom span.
/// </summary>
public class OrderService
{
    public async Task<Order> ProcessOrderAsync(string orderId, IEnumerable<OrderItem> items)
    {
        // StartActivity creates a span. The using block ensures it ends even on exception.
        using var activity = OrdersTelemetry.Source.StartActivity(
            "process-order",
            ActivityKind.Internal
        );

        // SetTag: equivalent to span.setAttribute().
        // Use OTel semantic convention names where they exist.
        activity?.SetTag("order.id", orderId);
        activity?.SetTag("order.item_count", items.Count());

        var stopwatch = System.Diagnostics.Stopwatch.StartNew();

        try
        {
            var order = await DoProcessOrderAsync(orderId, items);

            activity?.SetStatus(ActivityStatusCode.Ok);

            // Record metrics alongside the span.
            stopwatch.Stop();
            OrdersTelemetry.OrdersProcessed.Add(1, new TagList {
                { "status", "success" },
                { "order.type", order.Type }
            });
            OrdersTelemetry.OrderDuration.Record(
                stopwatch.Elapsed.TotalMilliseconds,
                new TagList { { "status", "success" } }
            );

            return order;
        }
        catch (Exception ex)
        {
            // SetStatus + AddException marks the span as failed with the exception details.
            activity?.SetStatus(ActivityStatusCode.Error, ex.Message);
            activity?.AddException(ex);

            stopwatch.Stop();
            OrdersTelemetry.OrderDuration.Record(
                stopwatch.Elapsed.TotalMilliseconds,
                new TagList { { "status", "error" } }
            );

            throw;
        }
    }

    /// <summary>
    /// Example: adding events to a span (timestamped annotations).
    /// </summary>
    public async Task ChargePaymentAsync(decimal amount)
    {
        using var activity = OrdersTelemetry.Source.StartActivity("charge-payment");
        activity?.SetTag("payment.amount_usd", (double)amount);

        // AddEvent: a timestamped log entry attached to this span.
        activity?.AddEvent(new ActivityEvent("payment-gateway-called",
            tags: new ActivityTagsCollection {
                { "gateway.name", "stripe" },
                { "payment.method", "card" }
            }
        ));

        await CallPaymentGatewayAsync(amount);

        activity?.AddEvent(new ActivityEvent("payment-gateway-response",
            tags: new ActivityTagsCollection {
                { "gateway.status", "success" }
            }
        ));
    }

    // Register ActivitySource in Program.cs (inside AddOpenTelemetry().WithTracing()):
    //
    //   builder.Services.AddOpenTelemetry()
    //     .WithTracing(tracing => tracing
    //       .AddAspNetCoreInstrumentation()
    //       .AddSource("MyCompany.Orders")   // ← registers this ActivitySource
    //       .AddOtlpExporter())
    //     .WithMetrics(metrics => metrics
    //       .AddAspNetCoreInstrumentation()
    //       .AddMeter("MyCompany.Orders")    // ← registers this Meter
    //       .AddOtlpExporter());

    private async Task<Order> DoProcessOrderAsync(string orderId, IEnumerable<OrderItem> items)
        => new Order(orderId, "standard");

    private async Task CallPaymentGatewayAsync(decimal amount) { }
}

public record Order(string Id, string Type);
public record OrderItem(string Sku, int Quantity);
