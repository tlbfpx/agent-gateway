import { http } from '../request';

/**
 * Readiness 报告（后端 /v1/ready，HealthController#ready）。
 * NOT_READY 时后端返回 503，request 封装会抛 ApiError，
 * 调用方需从 ApiError.payload 中取回 body。
 */
export interface ReadyReport {
  status: 'READY' | 'NOT_READY';
  checks?: Record<string, ComponentStatus>;
}

/** 兼容字符串（"UP"/"EMPTY"）与对象（{status, details}）两种检查项形态 */
export type ComponentStatus =
  | string
  | { status: string; details?: Record<string, unknown> };

/** Liveness（进程存活探测，仅 status，无组件明细） */
export interface HealthReport {
  status: 'UP' | 'DOWN';
}

export const getHealth = () => http.get<HealthReport>('/health');

export const getReady = () => http.get<ReadyReport>('/ready');
