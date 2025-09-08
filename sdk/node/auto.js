/**
 * Node.js auto-instrumentation — zero-code OTEL setup.
 *
 * REQUIRED: This file must be the VERY FIRST require() in your entry point.
 *   require('./auto');  // line 1 of server.js
 *
 * REQUIRED ENV VARS:
 *   OTEL_SERVICE_NAME             - your service name (e.g. "api-gateway")
 *   OTEL_EXPORTER_OTLP_ENDPOINT  - Collector address (e.g. "http://localhost:4317")
 *
 * OPTIONAL ENV VARS:
 *   OTEL_RESOURCE_ATTRIBUTES     - "deployment.environment=local,service.version=1.0.0"
 *   OTEL_LOG_LEVEL               - "debug" to see SDK internals, "error" to silence them
 *
 * VERIFY IT'S WORKING:
 *   Make one request to your app, then:
 *   docker logs otel-collector --tail 30
 *   Look for ScopeSpans with your service name.
 */

"use strict";

const { NodeSDK } = require("@opentelemetry/sdk-node");
const { getNodeAutoInstrumentations } = require("@opentelemetry/auto-instrumentations-node");
const { OTLPTraceExporter } = require("@opentelemetry/exporter-trace-otlp-grpc");
const { OTLPMetricExporter } = require("@opentelemetry/exporter-metrics-otlp-grpc");
const { PeriodicExportingMetricReader } = require("@opentelemetry/sdk-metrics");
const { Resource } = require("@opentelemetry/resources");
const { ATTR_SERVICE_NAME } = require("@opentelemetry/semantic-conventions");

const sdk = new NodeSDK({
  resource: new Resource({
    [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || "unknown-node-service",
  }),
  traceExporter: new OTLPTraceExporter(),
  metricReader: new PeriodicExportingMetricReader({
    exporter: new OTLPMetricExporter(),
    exportIntervalMillis: 30_000,
  }),
  // getNodeAutoInstrumentations() enables all supported instrumentations automatically.
  // To disable noisy ones: getNodeAutoInstrumentations({ '@opentelemetry/instrumentation-dns': { enabled: false } })
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

process.on("SIGTERM", () => {
  sdk.shutdown().finally(() => process.exit(0));
});
