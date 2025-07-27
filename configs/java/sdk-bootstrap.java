/**
 * OpenTelemetry manual SDK bootstrap for Java.
 *
 * WHEN TO USE THIS FILE:
 *   This is for when you need manual control over the SDK — custom span processors,
 *   custom exporters, or programmatic configuration. For most Spring Boot applications,
 *   the zero-code javaagent approach (see README.md) is simpler and covers more libraries.
 *
 * REQUIRED ENV VARS:
 *   OTEL_SERVICE_NAME              - shown on every span and metric (e.g. "payments-api")
 *   OTEL_EXPORTER_OTLP_ENDPOINT   - Collector address (e.g. "http://localhost:4317")
 *
 * OPTIONAL ENV VARS:
 *   OTEL_RESOURCE_ATTRIBUTES      - e.g. "deployment.environment=local,service.version=1.0.0"
 *
 * MAVEN DEPENDENCIES — add to pom.xml:
 *   <dependency>
 *     <groupId>io.opentelemetry</groupId>
 *     <artifactId>opentelemetry-sdk</artifactId>
 *     <version>1.40.0</version>
 *   </dependency>
 *   <dependency>
 *     <groupId>io.opentelemetry</groupId>
 *     <artifactId>opentelemetry-exporter-otlp</artifactId>
 *     <version>1.40.0</version>
 *   </dependency>
 *
 * VERIFY IT'S WORKING:
 *   Run your app, make one request, then:
 *     docker logs otel-collector --tail 30
 *   You should see ScopeSpans with your service name.
 */

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;

import java.time.Duration;

public class OpenTelemetryBootstrap {

    /**
     * Call this once at application startup, before any instrumented code runs.
     * In Spring Boot, call from a @PostConstruct or ApplicationRunner bean.
     * In plain Java, call from main() before starting any server.
     */
    public static void initialize() {
        String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "unknown-java-service");
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        // Resource: attributes that appear on every span and metric this JVM emits.
        Resource resource = Resource.getDefault()
            .merge(Resource.create(
                io.opentelemetry.api.common.Attributes.of(
                    ResourceAttributes.SERVICE_NAME, serviceName
                )
            ));

        // Trace exporter: sends spans to the Collector over gRPC.
        OtlpGrpcSpanExporter traceExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            // timeout: how long to wait for the Collector to acknowledge an export.
            // 10s is generous; tune down to 5s if you need faster failure detection.
            .setTimeout(Duration.ofSeconds(10))
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            // BatchSpanProcessor buffers spans before exporting.
            // SimpleSpanProcessor (sync) adds per-span latency — don't use it in servers.
            .addSpanProcessor(BatchSpanProcessor.builder(traceExporter)
                .setMaxQueueSize(2048)
                .setMaxExportBatchSize(512)
                .setScheduleDelay(Duration.ofMillis(5000))
                .build())
            .build();

        // Metric exporter: sends metrics to the Collector over gRPC.
        OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                // interval: how often metrics are flushed. 30s is reasonable.
                .setInterval(Duration.ofSeconds(30))
                .build())
            .build();

        // Register as the global provider so any library using GlobalOpenTelemetry
        // (including the javaagent, if attached) picks up this config.
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .buildAndRegisterGlobal();

        // Flush pending telemetry on JVM shutdown so the last spans aren't lost.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            tracerProvider.shutdown();
            meterProvider.shutdown();
        }));
    }

    /**
     * Example: creating a custom span around a business operation.
     * Get a tracer from the global provider after initialize() has been called.
     */
    public static void exampleCustomSpan() {
        Tracer tracer = GlobalOpenTelemetry.getTracer("com.example.my-component");

        Span span = tracer.spanBuilder("process-payment")
            .setAttribute("payment.method", "card")
            .setAttribute("payment.amount_usd", 42.00)
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // your business logic here
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
