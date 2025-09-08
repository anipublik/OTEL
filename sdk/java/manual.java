/**
 * Java manual instrumentation — custom spans, attributes, and metrics.
 *
 * Use this when:
 *   - The javaagent is attached and you want ADDITIONAL custom spans on top of auto-instrumentation
 *   - The javaagent can't be used (class loading constraints, OSGi, etc.)
 *   - You need fine-grained control over sampling decisions per operation
 *
 * MAVEN DEPENDENCIES:
 *   <dependency>
 *     <groupId>io.opentelemetry</groupId>
 *     <artifactId>opentelemetry-api</artifactId>
 *     <version>1.40.0</version>
 *   </dependency>
 *   <!-- Only needed when NOT using the javaagent: -->
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
 * WHEN USING THE JAVAAGENT: skip the SDK initialization — just use GlobalOpenTelemetry.
 * WHEN NOT USING THE JAVAAGENT: call initialize() from main() before starting the server.
 *
 * VERIFY IT'S WORKING:
 *   docker logs otel-collector --tail 30
 *   Look for ScopeSpans with "manual-spans" as the instrumentation scope.
 */

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public class ManualInstrumentation {

    // Get a tracer scoped to this component.
    // If the javaagent is attached, GlobalOpenTelemetry already has a provider configured.
    // If not, call initialize() first (see configs/java/sdk-bootstrap.java).
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer(
        "com.example.my-component",
        "1.0.0"
    );

    private static final Meter meter = GlobalOpenTelemetry.getMeter("com.example.my-component");

    // Counter: accumulates over time. Good for "how many times did X happen."
    private static final LongCounter ordersCounter = meter
        .counterBuilder("orders.processed")
        .setDescription("Total orders successfully processed")
        .setUnit("1")
        .build();

    // Histogram: records distributions. Good for latency, payload sizes.
    private static final LongHistogram paymentDuration = meter
        .histogramBuilder("payment.duration")
        .setDescription("Payment processing time in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build();

    /**
     * Wrap a business operation in a custom span.
     * Use try-with-resources to guarantee the span ends even on exception.
     */
    public static Object processOrder(String orderId, int itemCount) {
        Span span = tracer.spanBuilder("process-order")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();

        // makeCurrent() registers the span as the active span in the current thread.
        // Any child spans created inside this try block will be linked to this span.
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("order.id", orderId);
            span.setAttribute("order.item_count", itemCount);

            Object result = doWork(orderId, itemCount);

            ordersCounter.add(1, Attributes.builder()
                .put("status", "success")
                .build());
            span.setStatus(StatusCode.OK);
            return result;

        } catch (Exception e) {
            // recordException attaches the full stack trace to the span.
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            ordersCounter.add(1, Attributes.builder()
                .put("status", "error")
                .put("error.type", e.getClass().getSimpleName())
                .build());
            throw e;

        } finally {
            // Always end the span. Forgetting this causes memory leaks and missing traces.
            span.end();
        }
    }

    /**
     * Example: adding events to a span.
     * Events are timestamped annotations on the span timeline.
     */
    public static void withSpanEvents() {
        Span span = tracer.spanBuilder("payment-flow").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.addEvent("gateway-called", Attributes.builder()
                .put("gateway.name", "stripe")
                .put("payment.method", "card")
                .build());

            // ... call payment gateway ...

            span.addEvent("gateway-response-received", Attributes.builder()
                .put("gateway.status", "success")
                .build());
        } finally {
            span.end();
        }
    }

    /**
     * Example: recording a histogram measurement with attributes.
     */
    public static void recordPaymentTiming(long durationMs, boolean success) {
        paymentDuration.record(durationMs, Attributes.builder()
            .put("payment.status", success ? "success" : "error")
            .build());
    }

    private static Object doWork(String orderId, int itemCount) {
        return new Object();
    }
}
