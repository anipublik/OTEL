# Kubernetes — OpenTelemetry setup

This config is for instrumenting a Kubernetes cluster itself — collecting pod metadata, node metrics, and enriching all spans and metrics with Kubernetes resource attributes (namespace, pod name, node name, deployment name).

For deploying the Collector *into* Kubernetes, see `infra/kubernetes/` (raw manifests) or `infra/helm/` (Helm chart).

---

## What this config does

1. **Enriches every span and metric** with K8s attributes via `k8sattributesprocessor`. When a pod emits a span, the Collector adds `k8s.pod.name`, `k8s.namespace.name`, `k8s.node.name`, `k8s.deployment.name` to it automatically.

2. **Collects cluster-level metrics** via `k8s_cluster` receiver: node count, pod count, container restarts, resource quotas, persistent volume status.

3. **Collects host metrics** (when running as DaemonSet) via `hostmetricsreceiver`: CPU, memory, disk I/O, network per node.

---

## Deployment topology

**DaemonSet (recommended starting point):**
- One Collector pod per node
- Receives OTLP from pods on that node via `hostNetwork: true` or a ClusterIP service
- Runs `hostmetricsreceiver` with host filesystem access

**Deployment (add later, for tail sampling):**
- A single Collector deployment with `tail_sampling` processor
- DaemonSet forwards to it via OTLP exporter
- Makes keep/drop decisions on complete traces

See `infra/kubernetes/` for both manifests.

---

## RBAC requirements

The `k8sattributesprocessor` and `k8s_cluster` receiver query the Kubernetes API. The Collector's ServiceAccount needs `get` and `list` access to:

- `pods`, `nodes`, `namespaces`, `endpoints`
- `replicationcontrollers`, `resourcequotas`
- `deployments`, `replicasets`, `statefulsets`, `daemonsets`, `jobs`, `cronjobs`

The full RBAC manifest is in `infra/kubernetes/rbac.yaml`. It uses a ClusterRole (not cluster-admin — minimum required verbs only).

---

## What you see in Grafana after setup

- Every span has `k8s.pod.name`, `k8s.namespace.name`, `k8s.deployment.name`
- In Tempo, you can filter traces by namespace or deployment
- In Prometheus, `k8s_pod_status_phase` and `k8s_container_restarts_total` are available
- The collector-health.json dashboard shows per-node Collector metrics
