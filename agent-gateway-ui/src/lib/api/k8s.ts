/**
 * k8s.ts — K8s CRD Gateway/Route 模拟 API client（Round 14 #k8s-crd §6）
 *
 * 后端实现细节：gateway-interfaces/k8s/K8sGatewayController.java
 * 端点完全模拟 K8s API server 路径格式。
 */

import { getAdminToken } from './admin';

export interface K8sListener {
  name: string;
  port: number;
  protocol: string;
  tls: boolean;
}

export interface K8sMatchRule {
  path: string;
  method?: string;
}

export interface K8sBackend {
  provider: string;
  weight: number;
  model?: string;
}

export interface K8sGateway {
  apiVersion: string;
  kind: string;
  metadata: { name: string; namespace: string };
  spec: { listeners: K8sListener[]; replicas: number };
  status?: { conditions: Array<{ type: string; status: string }> };
}

export interface K8sRoute {
  apiVersion: string;
  kind: string;
  metadata: { name: string; namespace: string };
  spec: { gatewayRef: string; match: K8sMatchRule[]; backends: K8sBackend[] };
}

export interface K8sReconcileResult {
  gateway: K8sGateway;
  listeners: Record<string, { port: number; protocol: string; tls: boolean }>;
  routes: Array<{ name: string; gatewayRef: string; match: any[]; backends: any[] }>;
}

const HDRS = () => ({ 'X-Admin-Token': getAdminToken(), 'Content-Type': 'application/json' });

async function req<T>(url: string, init: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${url} → ${res.status}`);
  if (res.status === 204) return undefined as unknown as T;
  return (await res.json()) as T;
}

// ============= Gateway =============

export const listGateways = (namespace = 'default') =>
  req<{ items: K8sGateway[] }>(
    `/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentgateways`,
    { method: 'GET', headers: HDRS() });

export const getGateway = (namespace: string, name: string) =>
  req<K8sGateway>(`/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentgateways/${name}`,
    { method: 'GET', headers: HDRS() });

export const applyGateway = (namespace: string, gw: K8sGateway) =>
  req<K8sGateway>(`/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentgateways`,
    { method: 'POST', headers: HDRS(), body: JSON.stringify(gw) });

export const deleteGateway = (namespace: string, name: string) =>
  req<{ deleted: boolean }>(`/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentgateways/${name}`,
    { method: 'DELETE', headers: HDRS() });

export const reconcileGateway = (namespace: string, name: string) =>
  req<K8sReconcileResult>(
    `/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentgateways/${name}/reconcile`,
    { method: 'GET', headers: HDRS() });

// ============= Route =============

export const listRoutes = (namespace = 'default') =>
  req<{ items: K8sRoute[] }>(
    `/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentroutes`,
    { method: 'GET', headers: HDRS() });

export const applyRoute = (namespace: string, route: K8sRoute) =>
  req<K8sRoute>(`/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentroutes`,
    { method: 'POST', headers: HDRS(), body: JSON.stringify(route) });

export const deleteRoute = (namespace: string, name: string) =>
  req<{ deleted: boolean }>(`/apis/gateway.agentgateway.io/v1alpha1/namespaces/${namespace}/agentroutes/${name}`,
    { method: 'DELETE', headers: HDRS() });