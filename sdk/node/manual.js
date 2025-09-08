/**
 * Node.js manual instrumentation — adding custom spans and attributes.
 *
 * Use this AFTER auto.js has initialized the SDK.
 * auto.js sets up the global TracerProvider; this file shows how to use it.
 *
 * REQUIRED: auto.js must be loaded first (line 1 of your entry point).
 */

"use strict";

const { trace, context, SpanStatusCode } = require("@opentelemetry/api");

// Get a tracer scoped to this component.
// The name here appears as the instrumentation scope in traces.
const tracer = trace.getTracer("my-component", "1.0.0");

/**
 * Example: wrap a business operation in a custom span.
 * Spans record timing, attributes, and errors automatically.
 */
async function processOrder(orderId, items) {
  return tracer.startActiveSpan("process-order", async (span) => {
    try {
      // Set attributes on the span. Use OTel semantic convention names where they exist.
      // https://opentelemetry.io/docs/specs/semconv/
      span.setAttribute("order.id", orderId);
      span.setAttribute("order.item_count", items.length);

      const result = await doWork(orderId, items);

      // Mark the span as successful.
      span.setStatus({ code: SpanStatusCode.OK });
      return result;
    } catch (err) {
      // Record the exception so it appears in Tempo's exception tab.
      span.recordException(err);
      span.setStatus({ code: SpanStatusCode.ERROR, message: err.message });
      throw err;
    } finally {
      // Always end the span, even on error.
      span.end();
    }
  });
}

/**
 * Example: create a child span within an existing trace.
 * startActiveSpan() propagates context automatically.
 */
async function fetchInventory(itemId) {
  return tracer.startActiveSpan("fetch-inventory", async (span) => {
    span.setAttribute("inventory.item_id", itemId);
    try {
      // Your fetch logic here. Any HTTP calls made inside this span that use
      // an instrumented client (like node-fetch or axios) will be child spans.
      const data = await fetch(`http://inventory-service/items/${itemId}`);
      return data.json();
    } finally {
      span.end();
    }
  });
}

/**
 * Example: adding events to a span.
 * Events are timestamped log entries attached to a span, not separate log records.
 * Use them for "something happened at this point in the request lifecycle."
 */
function addSpanEvents(span) {
  // addEvent: timestamped annotation on the span. Searchable in Tempo.
  span.addEvent("cache-miss", {
    "cache.key": "user:42",
    "cache.backend": "redis",
  });

  span.addEvent("db-query-start");
  // ... run query ...
  span.addEvent("db-query-end", { "db.rows_returned": 15 });
}

module.exports = { processOrder, fetchInventory, addSpanEvents };
