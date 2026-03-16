/**
 * Java custom metrics template — all six OpenTelemetry instrument types.
 *
 * The javaagent initializes GlobalOpenTelemetry at JVM startup.
 * Call GlobalOpenTelemetry.getMeter() anywhere after main() starts — no SDK setup needed.
 * If running WITHOUT the javaagent, call initialize() from configs/java/sdk-bootstrap.java first.
 *
 * MAVEN DEPENDENCY (compile-time API only — SDK is provided by the agent at runtime):
 *   <dependency>
 *     <groupId>io.opentelemetry</groupId>
 *     <artifactId>opentelemetry-api</artifactId>
 *     <version>1.43.0</version>
 *   </dependency>
 *
 * REQUIRED ENV VARS:
 *   OTEL_SERVICE_NAME             - e.g. "checkout-api"
 *   OTEL_EXPORTER_OTLP_ENDPOINT  - e.g. "http://localhost:4317"
 *   OTEL_METRICS_EXPORTER=otlp   - tell the agent to export metrics via OTLP
 *
 * VERIFY IT'S WORKING:
 *   docker logs otel-collector --tail 30
 *   Query Prometheus: http://localhost:9090
 *   All metrics are prefixed with "otel_" by the Collector's prometheus exporter.
 *
 * INSTRUMENT TYPES — pick the right one:
 *
 *   Counter                  Always goes up. Request counts, bytes sent, errors.
 *   UpDownCounter            Goes up and down. Active connections, queue depth, cache size.
 *   LongHistogram            Measures distributions. Latency (ms), payload size, retry count.
 *   ObservableGauge          Snapshot of a value you poll. Memory, CPU %, config flag.
 *   ObservableCounter        Monotonically increasing value you poll. JVM GC runs, uptime ticks.
 *   ObservableUpDownCounter  Up/down value you poll. Thread pool size, open file handles.
 */

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongUpDownCounter;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

public class AppMetrics {

    // One meter per logical component. The name becomes the instrumentation scope.
    private static final Meter meter = GlobalOpenTelemetry.getMeter("com.example.checkout", "1.0.0");

    // ─── Counter ──────────────────────────────────────────────────────────────
    // Use when: a value only ever increases and you want the rate of change.
    // Prometheus query: rate(otel_orders_total[5m])
    private static final LongCounter ordersCounter = meter
        .counterBuilder("orders")
        .setDescription("Total orders submitted")
        .setUnit("1")
        .build();

    public static void recordOrder(String status, String region) {
        // Attributes are the Prometheus label dimensions for this metric.
        // Keep cardinality low: "status" and "region" are fine; "userId" is not.
        ordersCounter.add(1, Attributes.of(
            AttributeKey.stringKey("order.status"),      status,
            AttributeKey.stringKey("deployment.region"), region
        ));
    }

    // ─── UpDownCounter ────────────────────────────────────────────────────────
    // Use when: a value goes both up and down and you need the current level.
    // Prometheus query: otel_active_checkouts
    private static final LongUpDownCounter activeCheckouts = meter
        .upDownCounterBuilder("active_checkouts")
        .setDescription("Number of checkout sessions currently in progress")
        .setUnit("1")
        .build();

    public static void startCheckout() { activeCheckouts.add(1); }
    public static void endCheckout()   { activeCheckouts.add(-1); }

    // ─── Histogram ────────────────────────────────────────────────────────────
    // Use when: you need percentile visibility (p50/p95/p99), not just averages.
    // Prometheus query: histogram_quantile(0.95, rate(otel_checkout_duration_ms_bucket[5m]))
    private static final LongHistogram checkoutDuration = meter
        .histogramBuilder("checkout.duration")
        .setDescription("End-to-end checkout latency in milliseconds")
        .setUnit("ms")
        .ofLongs()
        .build();

    public static <T> T timedCheckout(CheckoutSupplier<T> fn, String method) throws Exception {
        long startMs = System.currentTimeMillis();
        try {
            T result = fn.get();
            checkoutDuration.record(
                System.currentTimeMillis() - startMs,
                Attributes.of(
                    AttributeKey.stringKey("payment.method"),   method,
                    AttributeKey.stringKey("checkout.status"),  "success"
                )
            );
            return result;
        } catch (Exception e) {
            checkoutDuration.record(
                System.currentTimeMillis() - startMs,
                Attributes.of(
                    AttributeKey.stringKey("payment.method"),   method,
                    AttributeKey.stringKey("checkout.status"),  "error"
                )
            );
            throw e;
        }
    }

    // ─── ObservableGauge ──────────────────────────────────────────────────────
    // Use when: you want to report the current value of something at collection time.
    // The callback is invoked by the SDK export cycle — keep it fast (no blocking I/O).
    // Prometheus query: otel_jvm_memory_used_bytes
    private static final MemoryMXBean memoryMX = ManagementFactory.getMemoryMXBean();

    // Store the registration handle to close it on shutdown if needed.
    private static final ObservableLongGauge jvmMemoryGauge = meter
        .gaugeBuilder("jvm.memory.used")
        .setDescription("JVM memory used by pool type")
        .setUnit("By")
        .ofLongs()
        .buildWithCallback(measurement -> {
            measurement.record(
                memoryMX.getHeapMemoryUsage().getUsed(),
                Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "heap")
            );
            measurement.record(
                memoryMX.getNonHeapMemoryUsage().getUsed(),
                Attributes.of(AttributeKey.stringKey("jvm.memory.type"), "non_heap")
            );
        });

    // ─── ObservableCounter ────────────────────────────────────────────────────
    // Use when: a counter is maintained externally (e.g., inside a library or
    // native layer) and you can only read its absolute current value.
    // The SDK converts successive absolute readings into a rate automatically.
    // Prometheus query: rate(otel_jvm_gc_collections_total[5m])
    private static final ObservableLongCounter gcCounter = meter
        .counterBuilder("jvm.gc.collections")
        .setDescription("Total JVM garbage collection runs since process start")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            ManagementFactory.getGarbageCollectorMXBeans().forEach(gc -> {
                if (gc.getCollectionCount() >= 0) {
                    measurement.record(
                        gc.getCollectionCount(),
                        Attributes.of(AttributeKey.stringKey("gc.name"), gc.getName())
                    );
                }
            });
        });

    // ─── ObservableUpDownCounter ──────────────────────────────────────────────
    // Use when: a pool or queue size is managed externally and you poll it
    // rather than instrumenting add/remove calls inline.
    // Prometheus query: otel_jvm_threads
    private static final ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

    private static final ObservableLongUpDownCounter threadGauge = meter
        .upDownCounterBuilder("jvm.threads")
        .setDescription("JVM thread counts by state")
        .setUnit("1")
        .buildWithCallback(measurement -> {
            measurement.record(
                threadMX.getThreadCount(),
                Attributes.of(AttributeKey.stringKey("thread.state"), "live")
            );
            measurement.record(
                threadMX.getDaemonThreadCount(),
                Attributes.of(AttributeKey.stringKey("thread.state"), "daemon")
            );
            measurement.record(
                threadMX.getPeakThreadCount(),
                Attributes.of(AttributeKey.stringKey("thread.state"), "peak")
            );
        });

    // ─── Batch observable — single callback populating multiple instruments ────
    // Read from one source (e.g., one JMX call, one HTTP request) and populate
    // several instruments at once to avoid redundant I/O.
    private static final io.opentelemetry.api.metrics.ObservableLongGauge heapGauge =
        meter.gaugeBuilder("jvm.heap.committed").ofLongs().setUnit("By").buildWithCallback(m ->
            m.record(memoryMX.getHeapMemoryUsage().getCommitted()));

    // ─── Application-level atomic counter (observable pattern) ────────────────
    // Use an AtomicLong as a thread-safe accumulator when you want to increment
    // inside hot paths and report the total via an ObservableCounter.
    // This avoids lock contention from calling the OTEL API on every request.
    private static final AtomicLong totalPaymentsProcessed = new AtomicLong(0);

    private static final ObservableLongCounter paymentsObservable = meter
        .counterBuilder("payments.processed")
        .setDescription("Total payment transactions processed since start")
        .setUnit("1")
        .buildWithCallback(measurement ->
            measurement.record(totalPaymentsProcessed.get())
        );

    // Call this from your payment handler — no OTEL API call on the hot path.
    public static void incrementPaymentsProcessed() {
        totalPaymentsProcessed.incrementAndGet();
    }

    @FunctionalInterface
    public interface CheckoutSupplier<T> {
        T get() throws Exception;
    }
}
