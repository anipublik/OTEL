# infra/helm/ — Helm chart for otel-starter

The Helm chart wraps the same Collector configs used in `infra/kubernetes/` and makes them configurable via `values.yaml`. Install the whole observability stack with one command.

---

## Prerequisites

- Helm 3.x
- Kubernetes cluster with `kubectl` configured

## Install

```bash
# Add the repo (once it's published to GitHub Pages)
helm repo add otel-starter https://anisricode.github.io/otel-starter
helm repo update

# Install with defaults (DaemonSet only, debug exporter)
helm install otel-starter otel-starter/otel-starter \
  --namespace otel-system \
  --create-namespace

# Install from local directory (development)
helm install otel-starter ./infra/helm \
  --namespace otel-system \
  --create-namespace
```

## Install with a cloud override file

```bash
# AWS
helm install otel-starter ./infra/helm \
  --namespace otel-system \
  --create-namespace \
  -f ./infra/helm/values-aws.yaml \
  --set exporters.cloud.aws.region=us-east-1

# GCP
helm install otel-starter ./infra/helm \
  --namespace otel-system \
  --create-namespace \
  -f ./infra/helm/values-gcp.yaml \
  --set exporters.cloud.gcp.project=my-project-id

# Azure
helm install otel-starter ./infra/helm \
  --namespace otel-system \
  --create-namespace \
  -f ./infra/helm/values-azure.yaml \
  --set exporters.cloud.azure.connectionString="InstrumentationKey=..."
```

## Upgrade

```bash
helm upgrade otel-starter ./infra/helm \
  --namespace otel-system \
  -f values-override.yaml
```

## Uninstall

```bash
helm uninstall otel-starter --namespace otel-system
```

---

## Values reference

All fields in `values.yaml` have inline comments. The key top-level sections are:

| Section | What it controls |
|---------|-----------------|
| `image` | Collector image and tag |
| `daemonset` | DaemonSet enabled/disabled, resources, tolerations |
| `deployment` | Gateway Deployment enabled/disabled, replicas, resources |
| `tailSampling` | Tail sampling processor config |
| `hpa` | HorizontalPodAutoscaler for the Deployment |
| `exporters.prometheus` | Prometheus scrape endpoint |
| `exporters.tempo` | Tempo OTLP exporter |
| `exporters.loki` | Loki log exporter |
| `exporters.cloud.aws` | AWS CloudWatch + X-Ray exporters |
| `exporters.cloud.gcp` | GCP Cloud Trace + Cloud Monitoring exporters |
| `exporters.cloud.azure` | Azure Monitor exporter |
| `rbac` | ServiceAccount and ClusterRole creation |
