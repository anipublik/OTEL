{{/*
_helpers.tpl — reusable template fragments for otel-starter chart.
These are called with {{ include "otel-starter.XXX" . }} in other templates.
*/}}

{{/*
otel-starter.name: chart name, truncated to 63 chars (K8s label value limit).
*/}}
{{- define "otel-starter.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
otel-starter.fullname: release-name + chart-name, truncated to 63 chars.
*/}}
{{- define "otel-starter.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
otel-starter.chart: chart name + version, used in the helm.sh/chart label.
*/}}
{{- define "otel-starter.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
otel-starter.labels: standard Helm labels applied to every resource.
*/}}
{{- define "otel-starter.labels" -}}
helm.sh/chart: {{ include "otel-starter.chart" . }}
{{ include "otel-starter.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: otel-starter
{{- end }}

{{/*
otel-starter.selectorLabels: labels used in selector matchLabels.
Must be stable — changing these requires deleting and recreating the DaemonSet/Deployment.
*/}}
{{- define "otel-starter.selectorLabels" -}}
app.kubernetes.io/name: {{ include "otel-starter.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
otel-starter.serviceAccountName: resolves the ServiceAccount name.
Uses the chart's SA when rbac.create is true, otherwise uses the provided name.
*/}}
{{- define "otel-starter.serviceAccountName" -}}
{{- if .Values.rbac.create }}
{{- include "otel-starter.fullname" . }}
{{- else }}
{{- .Values.rbac.serviceAccountName }}
{{- end }}
{{- end }}
