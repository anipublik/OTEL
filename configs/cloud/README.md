# configs/cloud/ — cloud-provider-specific Collector configs

These configs send telemetry to cloud-native backends instead of the open stack (Prometheus + Tempo + Loki). Use them when you're running in a cloud and want to use that cloud's managed observability services.

---

## When to use cloud configs vs the open stack

| Situation | Use |
|-----------|-----|
| Local development | `infra/docker/` (open stack — Grafana + Tempo + Loki) |
| Staging on AWS, want full control | `infra/helm/` + open stack on EKS |
| Production on AWS, want CloudWatch alarms + X-Ray trace search | `configs/cloud/aws/` |
| Production on GCP | `configs/cloud/gcp/` |
| Production on Azure | `configs/cloud/azure/` |

Cloud configs trade observability flexibility for operational simplicity. CloudWatch alarms, GCP Cloud Monitoring alert policies, and Azure Monitor alerts work natively with their respective exporters. The tradeoff: you're locked into that cloud's UI and query language.

---

## Subdirectories

- `aws/` — CloudWatch (metrics), X-Ray (traces), S3 (log archival)
- `gcp/` — Cloud Trace (traces), Cloud Monitoring (metrics)
- `azure/` — Azure Monitor (traces + metrics via Application Insights)

---

## Shared rules for all cloud configs

1. **No hardcoded credentials.** Every secret is `${ENV_VAR_NAME}` with a comment.
2. **`debug` exporter is always present** (commented out in the pipeline, ready to uncomment for troubleshooting — cloud exporters can be opaque when they fail).
3. **`memory_limiter` and `batch` are always present.** Cloud APIs have rate limits.
4. **Cost notes are included** where relevant — cloud observability can get expensive fast.
