{{/* agent-gateway Helm chart helpers.
    命名约定：所有资源全名 = "<release>-<chart>[-<suffix>]"，确保跨 release 不冲突。
*/}}

{{/* Chart name (lowercase, kebab). */}}
{{- define "agent-gateway.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Full resource name with release prefix. */}}
{{- define "agent-gateway.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Chart name + version for label. */}}
{{- define "agent-gateway.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified app version label. */}}
{{- define "agent-gateway.appVersion" -}}
{{- .Chart.AppVersion | quote -}}
{{- end -}}

{{/* Labels common to all resources. */}}
{{- define "agent-gateway.labels" -}}
helm.sh/chart: {{ include "agent-gateway.chart" . }}
app.kubernetes.io/name: {{ include "agent-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ include "agent-gateway.appVersion" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: agent-gateway
{{- end -}}

{{/* Selector labels (stable across upgrades). */}}
{{- define "agent-gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ include "agent-gateway.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* ServiceAccount name. */}}
{{- define "agent-gateway.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "agent-gateway.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Secret name (existing or generated). */}}
{{- define "agent-gateway.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-secrets" (include "agent-gateway.fullname" .) -}}
{{- end -}}
{{- end -}}