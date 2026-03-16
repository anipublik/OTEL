package com.example.payments.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

/**
 * Custom OpenTelemetry instrumentation for the payments domain.
 *
 * The javaagent initializes GlobalOpenTelemetry at JVM startup — this class
 * only uses the API. Metrics export via OTLP to the Collector on port 4317.
 *
 * Metric names use the "payments." prefix so they are easy to find in Prometheus/Grafana.
 * The Collector's prometheus exporter adds an "otel_" namespace prefix on scrape.
 */
@Component
public class OrderMetrics {

    private static final String INSTRUMENTATION_SCOPE = "com.example.payments";

    private final Tracer tracer;
    private final LongCounter ordersCreated;
    private final LongHistogram checkoutDuration;
    private final LongUpDownCounter activeCheckouts;

    public OrderMetrics() {
        Meter meter = GlobalOpenTelemetry.getMeter(INSTRUMENTATION_SCOPE);

        this.tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);

        this.ordersCreated = meter
                .counterBuilder("payments.orders.created")
                .setDescription("Total orders created")
                .setUnit("1")
                .build();

        this.checkoutDuration = meter
                .histogramBuilder("payments.checkout.duration")
                .setDescription("Time to complete a checkout in milliseconds")
                .setUnit("ms")
                .ofLongs()
                .build();

        this.activeCheckouts = meter
                .upDownCounterBuilder("payments.active_checkouts")
                .setDescription("Number of checkouts currently in progress")
                .setUnit("1")
                .build();
    }

    public void recordOrderCreated(boolean success) {
        ordersCreated.add(1, Attributes.builder()
                .put("payments.status", success ? "created" : "failed")
                .build());
    }

    public void recordCheckoutDuration(long durationMs, boolean success, String method) {
        checkoutDuration.record(durationMs, Attributes.builder()
                .put("payments.status", success ? "success" : "error")
                .put("payments.method", method)
                .build());
    }

    public ActiveCheckout trackCheckout() {
        activeCheckouts.add(1);
        return new ActiveCheckout(activeCheckouts);
    }

    /**
     * Runs a block of work inside a custom span named "process-payment".
     * The span is a child of the auto-instrumented Spring MVC span.
     */
    public <T> T inPaymentSpan(String orderId, double amountUsd, String method, PaymentWork<T> work)
            throws Exception {
        Span span = tracer.spanBuilder("process-payment")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        span.setAttribute("payments.order_id", orderId);
        span.setAttribute("payments.amount_usd", amountUsd);
        span.setAttribute("payments.method", method);

        try (Scope scope = span.makeCurrent()) {
            T result = work.run();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    @FunctionalInterface
    public interface PaymentWork<T> {
        T run() throws Exception;
    }

    public static final class ActiveCheckout implements AutoCloseable {
        private final LongUpDownCounter counter;

        ActiveCheckout(LongUpDownCounter counter) {
            this.counter = counter;
        }

        @Override
        public void close() {
            counter.add(-1);
        }
    }
}
