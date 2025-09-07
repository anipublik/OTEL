# Kafka — OpenTelemetry setup

Kafka instrumentation covers two concerns: producer spans (when a message is published), consumer spans (when a message is processed), and — critically — context propagation so these spans are linked into the same trace across service boundaries.

---

## How Kafka context propagation works

When a producer publishes a message, the SDK injects the current trace context into the message headers (`traceparent`, `tracestate`). When a consumer reads the message, it extracts the context from those headers and creates a child span. This links producer and consumer spans into one trace, even across different services and languages.

**This is only automatic if both sides use an SDK with Kafka instrumentation.** If one side doesn't propagate context, the trace breaks and you get orphan consumer spans.

---

## Prerequisites

- Kafka cluster (local: `bitnami/kafka` or `confluentinc/cp-kafka`)
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Instrumentation by language

**Node.js (`kafkajs`):**
```bash
npm install @opentelemetry/instrumentation-kafkajs
```
The `getNodeAutoInstrumentations()` call in `configs/node/sdk-bootstrap.js` includes this automatically when `kafkajs` is installed.

**Python (`confluent-kafka` or `kafka-python`):**
```bash
pip install opentelemetry-instrumentation-confluent-kafka
# or:
pip install opentelemetry-instrumentation-kafka-python
```
Then add to your bootstrap: `KafkaInstrumentor().instrument()`

**Java (Spring Kafka, plain consumer):**
The Java agent covers Kafka automatically via `kafka-clients`. No code changes needed.

**Go (`segmentio/kafka-go`, `Shopify/sarama`):**
- `kafka-go`: `go.opentelemetry.io/contrib/instrumentation/github.com/segmentio/kafka-go/otelsarama`
- `sarama`: `go.opentelemetry.io/contrib/instrumentation/github.com/Shopify/sarama/otelsarama`

---

## Verify context propagation is working

After sending a test message:

1. Open Grafana at `http://localhost:3000`
2. Go to Explore → Tempo
3. Search for a span with `messaging.system = kafka`
4. Expand the trace — you should see a producer span and a consumer span connected

If the consumer span is disconnected (no parent), the producer is not injecting headers or the consumer is not extracting them.

---

## Collector config notes

`collector.yaml` in this directory adds the `kafkareceiver` for consuming raw OTLP data published to a Kafka topic. This is useful when your app can't reach the Collector directly — it publishes telemetry to Kafka instead, and the Collector reads from it.

This is different from instrumenting Kafka message processing: the `kafkareceiver` moves telemetry data through Kafka, not your application messages.
