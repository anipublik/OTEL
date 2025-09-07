# Azure — OpenTelemetry Collector configs

One config that covers both traces and metrics via Azure Monitor (Application Insights).

---

## Authentication

**Option 1 — Connection string (local dev / simple deployments):**

```bash
export AZURE_MONITOR_CONNECTION_STRING="InstrumentationKey=...;IngestionEndpoint=..."
```

Copy the connection string from: Azure Portal → Application Insights → Your resource → Overview → Connection String.

**Option 2 — Managed Identity (production — recommended):**

Assign the `Monitoring Metrics Publisher` role to the Collector's managed identity:

```bash
az role assignment create \
  --assignee <managed-identity-object-id> \
  --role "Monitoring Metrics Publisher" \
  --scope /subscriptions/<subscription-id>/resourceGroups/<rg>/providers/microsoft.insights/components/<appinsights-name>
```

When using managed identity, remove `connection_string` from the exporter config and let the SDK use the credential chain automatically.

---

## Azure Monitor adaptive sampling — important interaction

Azure Monitor (Application Insights) performs its own adaptive sampling on the server side. If you also use the `tail_sampling` processor in the Collector, the two samplers can conflict:

- The Collector's tail sampler decides to **keep** a trace
- Azure Monitor's adaptive sampler independently decides to **drop** it

This means your Collector sampling rates won't be honored end-to-end. To fix this, disable adaptive sampling on the Application Insights resource side and let the Collector's sampler be the sole decision-maker. See the comment in `collector-monitor.yaml` for the exact config to disable AI sampling.
