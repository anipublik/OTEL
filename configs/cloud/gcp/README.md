# GCP — OpenTelemetry Collector configs

Two configs for GCP backends:

| Config | Backend | Signal |
|--------|---------|--------|
| `collector-cloudtrace.yaml` | Cloud Trace | Traces |
| `collector-monitoring.yaml` | Cloud Monitoring | Metrics |

Both use `googlecloudexporter` from `opentelemetry-collector-contrib`.

---

## Authentication

**Option 1 — Service account key (local dev / CI):**

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
export GCP_PROJECT_ID=my-project-id
```

**Option 2 — Workload Identity (GKE — recommended for production):**

Annotate the Collector's Kubernetes ServiceAccount with the GCP service account email:

```yaml
metadata:
  annotations:
    iam.gke.io/gcp-service-account: otel-collector@MY_PROJECT.iam.gserviceaccount.com
```

Bind the GCP service account to the K8s ServiceAccount using Workload Identity Federation. No key file needed.

**Required IAM roles:**

For Cloud Trace: `roles/cloudtrace.agent`  
For Cloud Monitoring: `roles/monitoring.metricWriter`

---

## Notes

**Cloud Trace span name limit:** Cloud Trace truncates span names at 128 bytes. Long span names (common with gRPC method paths) are silently truncated. Add a `transform` processor to shorten names before export if this matters.

**Cloud Monitoring metric prefix:** All OTEL metrics land under `custom.googleapis.com/` by default. This affects alerting policy naming. To use a custom prefix, set `metric_prefix` in the exporter config.
