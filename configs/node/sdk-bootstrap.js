/**
 * OpenTelemetry SDK bootstrap for Node.js
 *
 * PASTE THIS FILE into your project and require it as the VERY FIRST LINE
 * of your entry point — before any other require/import.
 *
 *   // server.js
 *   require('./sdk-bootstrap');  // must be line 1
 *   const express = require('express');
 *
 * REQUIRED ENV VARS — set these before starting your app:
 *
 *   OTEL_SERVICE_NAME              - shown on every span, metric, and log (e.g. "payments-api")
 *   OTEL_EXPORTER_OTLP_ENDPOINT   - where to send telemetry (e.g. "http://localhost:4317")
 *
 * OPTIONAL ENV VARS:
 *
 *   OTEL_RESOURCE_ATTRIBUTES      - comma-separated key=value pairs added to every signal
 *                                    e.g. "deployment.environment=local,service.version=1.2.0"
 *
 * VERIFY IT'S WORKING:
 *   Make one HTTP request to your app, then run:
 *     docker logs otel-collector --tail 30
 *   You should see span records with your service name, HTTP method, and status code.
 */

"use strict";

const { NodeSDK } = require("@opentelemetry/sdk-node");
const {
  getNodeAutoInstrumentations,
} = require("@opentelemetry/auto-instrumentations-node");
const {
  OTLPTraceExporter,
} = require("@opentelemetry/exporter-trace-otlp-grpc");
const {
  OTLPMetricExporter,
} = require("@opentelemetry/exporter-metrics-otlp-grpc");
const {
  OTLPLogExporter,
} = require("@opentelemetry/exporter-logs-otlp-grpc");
const {
  PeriodicExportingMetricReader,
} = require("@opentelemetry/sdk-metrics");
const {
  LoggerProvider,
  SimpleLogRecordProcessor,
} = require("@opentelemetry/sdk-logs");
const { Resource } = require("@opentelemetry/resources");
const {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} = require("@opentelemetry/semantic-conventions");

// The SDK reads OTEL_EXPORTER_OTLP_ENDPOINT from the environment automatically.
// Explicit endpoint construction here makes the config visible and overridable per exporter.
const collectorEndpoint =
  process.env.OTEL_EXPORTER_OTLP_ENDPOINT || "http://localhost:4317";

const sdk = new NodeSDK({
  // Resource attributes appear on every span, metric, and log this SDK emits.
  // OTEL_SERVICE_NAME and OTEL_RESOURCE_ATTRIBUTES env vars are read automatically by the SDK —
  // you only need to set them in the environment, not here.
  resource: new Resource({
    [ATTR_SERVICE_NAME]:
      process.env.OTEL_SERVICE_NAME || "unknown-node-service",
    [ATTR_SERVICE_VERSION]: process.env.npm_package_version || "0.0.0",
  }),

  traceExporter: new OTLPTraceExporter({
    url: collectorEndpoint,
  }),

  metricReader: new PeriodicExportingMetricReader({
    exporter: new OTLPMetricExporter({
      url: collectorEndpoint,
    }),
    // exportIntervalMillis: how often metrics are flushed to the Collector.
    // 30s is a reasonable default. Lower = more freshness, more network calls.
    exportIntervalMillis: 30_000,
  }),

  // getNodeAutoInstrumentations() enables all supported instrumentations.
  // To disable a specific library (e.g. DNS spans are noisy), pass overrides:
  //   getNodeAutoInstrumentations({ '@opentelemetry/instrumentation-dns': { enabled: false } })
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

// Flush pending telemetry on clean shutdown so you don't lose the last few spans.
process.on("SIGTERM", () => {
  sdk
    .shutdown()
    .then(() => process.exit(0))
    .catch((err) => {
      console.error("Error shutting down OTEL SDK", err);
      process.exit(1);
    });
});
