# PostgreSQL — OpenTelemetry setup

PostgreSQL instrumentation covers two distinct signals:

1. **Database metrics** — connection pool size, query latency percentiles, row counts, buffer hit rates — via `postgresqlreceiver` in the Collector.
2. **Query-level spans** — individual SQL queries as spans in your traces — via the app-side SDK instrumentation.

---

## Prerequisites

- PostgreSQL 10+ (the receiver uses the `pg_stat_database` and `pg_stat_bgwriter` views)
- A monitoring user with `pg_monitor` role
- The Collector running locally (`docker compose up -d` from `infra/docker/`)

## Create a monitoring user

```sql
CREATE USER otel_monitor WITH PASSWORD 'changeme';
GRANT pg_monitor TO otel_monitor;
```

The `pg_monitor` role grants read access to all `pg_stat_*` views without superuser privileges.

## Set environment variables for the Collector

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_USER=otel_monitor
export POSTGRES_PASSWORD=changeme     # set: never hardcode
export POSTGRES_DATABASE=mydb
```

## App-side query span instrumentation

Use your language's SQL instrumentation to get per-query spans:

**Python (SQLAlchemy):**
```python
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
SQLAlchemyInstrumentor().instrument(engine=engine)
```

**Node.js (pg):**
```bash
npm install @opentelemetry/instrumentation-pg
```
Included automatically in `getNodeAutoInstrumentations()`.

**Java:** The Java agent instruments JDBC automatically. Query text is captured by default (disable with `-Dotel.instrumentation.jdbc.statement-sanitizer.enabled=false`).

**Go:** Use `go.opentelemetry.io/contrib/instrumentation/database/sql/otelsql`:
```go
db, err := otelsql.Open("pgx", dsn)
```

## Verify it's working

After setting env vars and restarting the Collector:

```bash
docker logs otel-collector --tail 30
```

You should see `postgresql.*` metrics being exported. In Grafana, navigate to Explore → Prometheus and query `postgresql_rows_returned_total`.

For query spans, make a database call from your app and check Tempo for spans with `db.system=postgresql`.

---

## Slow query spans via sqlcommenter

[sqlcommenter](https://google.github.io/sqlcommenter/) is a library that appends trace context as SQL comments to every query:

```sql
SELECT * FROM orders -- traceparent='00-abc...',tracestate=''
```

This links slow query logs (from PostgreSQL's `log_min_duration_statement`) to your traces. The integration is app-side; the Collector config here doesn't change.
