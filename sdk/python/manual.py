"""
Python manual instrumentation — adding custom spans, attributes, and metrics.

Use this AFTER auto.py has initialized the TracerProvider and MeterProvider.
auto.py sets up the global providers; this module shows how to use them.
"""

from opentelemetry import trace, metrics
from opentelemetry.trace import SpanKind, StatusCode

# Get a tracer scoped to this component.
# The name appears as the instrumentation scope in Tempo's trace view.
tracer = trace.get_tracer("my-component", "1.0.0")

# Get a meter for recording custom metrics.
meter = metrics.get_meter("my-component", "1.0.0")

# Create a counter: records cumulative counts (orders processed, errors, cache misses).
orders_counter = meter.create_counter(
    "orders.processed",
    description="Total orders successfully processed",
    unit="1",
)

# Create a histogram: records distributions (latency, payload size, queue depth).
payment_duration = meter.create_histogram(
    "payment.duration",
    description="Time to process a payment in milliseconds",
    unit="ms",
)


def process_order(order_id: str, items: list) -> dict:
    """Wrap a business operation in a custom span."""
    # start_as_current_span: creates a span and makes it active in the current context.
    # Child spans created inside (by instrumented libraries) will be linked to this one.
    with tracer.start_as_current_span(
        "process-order",
        # kind: SERVER = inbound request, CLIENT = outbound call, INTERNAL = internal work.
        kind=SpanKind.INTERNAL,
    ) as span:
        # Set attributes using OTel semantic convention names where they exist.
        # Invented names are fine for domain-specific data.
        span.set_attribute("order.id", order_id)
        span.set_attribute("order.item_count", len(items))

        try:
            result = _do_work(order_id, items)

            # Record a metric alongside the span.
            orders_counter.add(1, {"status": "success", "region": "us-east-1"})
            span.set_status(StatusCode.OK)
            return result

        except Exception as e:
            # record_exception: attaches the stack trace to the span.
            span.record_exception(e)
            span.set_status(StatusCode.ERROR, str(e))
            orders_counter.add(1, {"status": "error"})
            raise


def charge_payment(amount_usd: float) -> bool:
    """Example: histogram measurement and span events."""
    import time

    with tracer.start_as_current_span("charge-payment") as span:
        span.set_attribute("payment.amount_usd", amount_usd)
        start = time.time()

        try:
            # Add a span event: a timestamped log entry attached to this span.
            span.add_event("payment-gateway-called", {"gateway": "stripe"})

            success = _call_payment_gateway(amount_usd)

            elapsed_ms = (time.time() - start) * 1000
            # Record to the histogram with attributes that become label dimensions in Prometheus.
            payment_duration.record(elapsed_ms, {"payment.status": "success"})

            return success
        except Exception as e:
            elapsed_ms = (time.time() - start) * 1000
            payment_duration.record(elapsed_ms, {"payment.status": "error"})
            span.record_exception(e)
            span.set_status(StatusCode.ERROR, str(e))
            raise


def _do_work(order_id, items):
    return {"order_id": order_id, "status": "processed"}


def _call_payment_gateway(amount):
    return True
