// OpenTelemetry SDK bootstrap for .NET / ASP.NET Core.
//
// Call AddOpenTelemetryInstrumentation() in Program.cs:
//
//   builder.Services.AddOpenTelemetryInstrumentation(builder.Configuration);
//
// REQUIRED ENV VARS (or appsettings.json under "OpenTelemetry"):
//
//   OTEL_SERVICE_NAME              - shown on every span/metric (e.g. "payments-api")
//   OTEL_EXPORTER_OTLP_ENDPOINT   - Collector address (e.g. "http://localhost:4317")
//
// OPTIONAL ENV VARS:
//
//   OTEL_RESOURCE_ATTRIBUTES      - "deployment.environment=local,service.version=1.0.0"
//
// NUGET PACKAGES REQUIRED:
//   OpenTelemetry.Extensions.Hosting
//   OpenTelemetry.Instrumentation.AspNetCore
//   OpenTelemetry.Instrumentation.Http
//   OpenTelemetry.Exporter.OpenTelemetryProtocol
//
// VERIFY IT'S WORKING:
//   Make one HTTP request, then: docker logs otel-collector --tail 30
//   You should see spans with http.request.method and your service name.

using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace OpenTelemetryBootstrap;

public static class OpenTelemetryExtensions
{
    public static IServiceCollection AddOpenTelemetryInstrumentation(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var serviceName = Environment.GetEnvironmentVariable("OTEL_SERVICE_NAME")
            ?? configuration["OpenTelemetry:ServiceName"]
            ?? "unknown-dotnet-service";

        var endpoint = Environment.GetEnvironmentVariable("OTEL_EXPORTER_OTLP_ENDPOINT")
            ?? configuration["OpenTelemetry:Endpoint"]
            ?? "http://localhost:4317";

        // Resource: attributes that appear on every span, metric, and log.
        var resourceBuilder = ResourceBuilder.CreateDefault()
            .AddService(serviceName)
            .AddEnvironmentVariableDetector(); // reads OTEL_RESOURCE_ATTRIBUTES

        services.AddOpenTelemetry()
            .WithTracing(tracing => tracing
                .SetResourceBuilder(resourceBuilder)
                // AspNetCore: inbound HTTP request spans with route, method, status code.
                .AddAspNetCoreInstrumentation(options =>
                {
                    // Filter out health check endpoints — they add noise without value.
                    options.Filter = context =>
                        !context.Request.Path.StartsWithSegments("/health") &&
                        !context.Request.Path.StartsWithSegments("/readyz");
                })
                // HttpClient: outbound HTTP call spans.
                .AddHttpClientInstrumentation(options =>
                {
                    // RecordException: attach exception details to the span on failure.
                    options.RecordException = true;
                })
                // OtlpExporter: sends spans to the Collector.
                .AddOtlpExporter(options =>
                {
                    options.Endpoint = new Uri(endpoint);
                    // Protocol defaults to Grpc. For HTTP, set:
                    // options.Protocol = OtlpExportProtocol.HttpProtobuf;
                }))
            .WithMetrics(metrics => metrics
                .SetResourceBuilder(resourceBuilder)
                // AspNetCore: request rate, error rate, duration histograms.
                // These feed the RED dashboard in Grafana.
                .AddAspNetCoreInstrumentation()
                // RuntimeInstrumentation: GC, thread pool, exception counts.
                // Install OpenTelemetry.Instrumentation.Runtime for this.
                // .AddRuntimeInstrumentation()
                .AddOtlpExporter(options =>
                {
                    options.Endpoint = new Uri(endpoint);
                    // PeriodicExportingMetricReader interval: 30s default.
                    // The SDK reads OTEL_METRIC_EXPORT_INTERVAL env var if set.
                }));

        return services;
    }
}

// Example: adding a custom span to a controller or service.
// Inject ITracer via DI (ASP.NET Core wires this up automatically when tracing is enabled).
//
// public class PaymentService
// {
//     private static readonly ActivitySource Activity = new("Payments");
//
//     public async Task ProcessPayment(decimal amount)
//     {
//         using var activity = Activity.StartActivity("process-payment");
//         activity?.SetTag("payment.amount_usd", amount);
//         // ... your logic
//     }
// }
