# Node.js / Express / Fastify — OpenTelemetry setup

This config covers any Node.js HTTP service. It works with Express, Fastify, Koa, plain `node:http`, and any framework that uses those primitives under the hood.

---

## Prerequisites

- Node.js 18+
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Install the SDK packages

```bash
npm install \
  @opentelemetry/sdk-node \
  @opentelemetry/auto-instrumentations-node \
  @opentelemetry/exporter-trace-otlp-grpc \
  @opentelemetry/exporter-metrics-otlp-grpc \
  @opentelemetry/exporter-logs-otlp-grpc \
  @grpc/grpc-js
```

## Add the bootstrap file

Copy `sdk-bootstrap.js` to your project root and require it **before any other import** in your entry point:

```js
// server.js  ← your entry point
require('./sdk-bootstrap.js');   // must be the very first line

const express = require('express');
// ... rest of your app
```

The `require` must come before any other module that might make HTTP calls or connect to a database — otherwise those libraries initialize without instrumentation and you miss spans.

## Set environment variables

```bash
export OTEL_SERVICE_NAME=my-service          # shown on every span and metric
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.version=1.0.0
```

Add these to your `.env` file or your process manager config. Do not hardcode them in the bootstrap.

## Verify it's working

Start your app and make one HTTP request. Then check the Collector debug output:

```bash
docker logs otel-collector --tail 50
```

You should see span records with your `service.name`, HTTP method, status code, and duration. If you see nothing, check that `OTEL_EXPORTER_OTLP_ENDPOINT` points at the right host and port.

---

## What gets instrumented automatically

The `@opentelemetry/auto-instrumentations-node` package covers:

- `http` / `https` — all inbound and outbound HTTP/HTTPS calls
- `express` — route-level spans
- `fastify` — route-level spans
- `grpc` — if you're using `@grpc/grpc-js`
- `dns` — DNS resolution spans
- `net` — TCP connect spans
- `pg`, `mysql2`, `mongodb`, `redis`, `ioredis` — if installed
- `kafkajs` — if installed

---

## Collector config notes

`collector.yaml` in this directory uses `otlpreceiver` on both gRPC (4317) and HTTP (4318). Node.js uses gRPC by default with `@grpc/grpc-js` installed; if you prefer HTTP, swap `OTEL_EXPORTER_OTLP_ENDPOINT` for `http://localhost:4318`.
