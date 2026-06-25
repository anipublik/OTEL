# infra/kubernetes/ — raw Kubernetes manifests

Deploy the OpenTelemetry Collector into a Kubernetes cluster without Helm. Everything in `infra/helm/` is generated from these manifests — use these if you can't or won't use Helm.

---

## Prerequisites

- `kubectl` configured to point at your cluster
- `kustomize` (included in `kubectl` v1.14+)
- Cluster with at least 1 node (any cloud or local: kind, minikube, k3s)

---

## Deploy

```bash
kubectl apply -k infra/kubernetes/
```

Kustomize applies manifests in the correct order: namespace → RBAC → ConfigMap → DaemonSet + Deployment + Service.

## Verify

```bash
# Collector DaemonSet pods are running
kubectl get pods -n otel-system

# RBAC was created
kubectl get clusterrole otel-collector -n otel-system

# Service is accessible
kubectl get svc -n otel-system
```

## Point your apps at the Collector

Within the cluster, use the ClusterIP service:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector.otel-system.svc.cluster.local:4317
```

Or use the node's IP directly (DaemonSet mode with `hostNetwork: true`):

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://$(NODE_IP):4317
```

---

## Two Collector deployment modes

**DaemonSet (`collector-daemonset.yaml`)** — one Collector pod per node. Receives OTLP from pods on that node. Runs `hostmetricsreceiver` for host CPU/memory/disk. Uses `collector-configmap-daemonset.yaml`.

**Deployment (`collector-deployment.yaml`)** — single Collector deployment for cluster-wide receivers (`k8s_cluster`) and optional `tail_sampling`. Uses `collector-configmap-gateway.yaml`. Does not run `hostmetrics` (that requires a per-node hostfs mount).

Deploy both by default. If you only need the DaemonSet:

```bash
kubectl apply -f infra/kubernetes/namespace.yaml
kubectl apply -f infra/kubernetes/rbac.yaml
kubectl apply -f infra/kubernetes/collector-configmap-daemonset.yaml
kubectl apply -f infra/kubernetes/collector-daemonset.yaml
kubectl apply -f infra/kubernetes/service.yaml
```

---

## Topology diagram

```
┌─────────────────────────────────────────────────────┐
│  Kubernetes Cluster                                   │
│                                                       │
│  ┌──────────┐    OTLP/gRPC    ┌──────────────────┐  │
│  │ App Pod  │ ──────────────► │ DaemonSet        │  │
│  │ (node 1) │                 │ Collector Pod    │  │
│  └──────────┘                 │ (node 1)         │  │
│                               └──────────────────┘  │
│  ┌──────────┐                          │             │
│  │ App Pod  │ ──────────────►          │ OTLP/gRPC   │
│  │ (node 2) │                ┌──────────────────┐   │
│  └──────────┘                │ DaemonSet        │   │
│                              │ Collector Pod    │   │
│                              │ (node 2)         │   │
│                              └──────────────────┘   │
│                                         │            │
│                               OTLP fwd (optional)   │
│                                         ▼            │
│                               ┌──────────────────┐  │
│                               │ Deployment       │  │
│                               │ Collector        │  │
│                               │ (tail sampling)  │  │
│                               └──────────────────┘  │
│                                         │            │
│                                 to backends          │
└─────────────────────────────────────────────────────┘
```
