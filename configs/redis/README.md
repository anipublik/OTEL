# Redis — OpenTelemetry setup

Redis instrumentation covers:

1. **Server metrics** — connected clients, memory usage, hit/miss rates, command latency — via `redisreceiver` in the Collector.
2. **Command-level spans** — individual Redis commands as spans — via app-side SDK instrumentation.

---

## Prerequisites

- Redis 6+ (the receiver uses the `INFO` command)
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Set environment variables for the Collector

```bash
export REDIS_ENDPOINT=localhost:6379
# If Redis has a password:
export REDIS_PASSWORD=yourpassword
```

## App-side command span instrumentation

**Python (redis-py):**
```python
from opentelemetry.instrumentation.redis import RedisInstrumentor
RedisInstrumentor().instrument()
```
Included automatically when using `opentelemetry-instrument` wrapper.

**Node.js (ioredis or node-redis):**
```bash
npm install @opentelemetry/instrumentation-ioredis
# or:
npm install @opentelemetry/instrumentation-redis-4
```
Both are included in `getNodeAutoInstrumentations()`.

**Java:** The Java agent instruments Jedis and Lettuce automatically.

**Go:** Use `go.opentelemetry.io/contrib/instrumentation/github.com/go-redis/redis/v9/otelredis`.

---

## What you see in Grafana

**Metrics (from `redisreceiver`):**
- `redis.clients.connected` — active client connections
- `redis.memory.used` — RSS and peak memory
- `redis.commands.processed` — ops/sec
- `redis.keyspace.hits` / `redis.keyspace.misses` — cache hit rate
- `redis.rdb.changes_since_last_save` — unsaved write pressure

**Spans (from app instrumentation):**
- Each Redis command (GET, SET, HGET, ZADD, etc.) is a span with `db.system=redis`, `db.statement` (the command), and the target key.
- Latency outliers stand out immediately in Tempo's trace view.

---

## Command-level span security note

By default, most Redis instrumentations capture the full command including key names and (for SET/HSET) the value. Values may contain PII. Disable value capture:

**Python:** `RedisInstrumentor().instrument(sanitize_query=True)`  
**Node.js:** Pass `dbStatementSerializer: () => "[sanitized]"` to the instrumentation config.
