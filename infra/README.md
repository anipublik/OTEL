# infra/ — deploy the Collector and observability stack

Three paths. Pick based on where you're running:

| Path | When to use |
|------|-------------|
| `infra/docker/` | Local development. One command. Grafana dashboards pre-provisioned. |
| `infra/kubernetes/` | Cluster deployment without Helm. Raw manifests. `kubectl apply -k .` |
| `infra/helm/` | Cluster deployment with Helm. Per-cloud override files included. Recommended for production. |

---

## Which path should I take?

**Start with `infra/docker/`** — always, even if your eventual target is K8s. Get telemetry flowing locally first, then move to K8s.

**Use `infra/kubernetes/`** if your team doesn't use Helm, or if you want to understand the raw manifests before adopting the Helm chart.

**Use `infra/helm/`** for production or staging clusters. The chart wraps the same manifests as `infra/kubernetes/` but makes them configurable and upgradeable.

---

## What all three share

All three deployment paths use the same Collector configs from `configs/`. The configs are mounted or templated — the YAML content is the single source of truth.
