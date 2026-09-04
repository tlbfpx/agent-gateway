/**
 * billing.ts — D2 计费/预算管理 API（spec §21.6 + GW-QUOTA-008）
 *
 * 后端：AdminBillingController（/v1/admin/billing/*）
 *   GET    /costs                     — 用量明细（真实 token 记账）
 *   GET    /costs/total               — 成本总额
 *   GET    /usage/export?format=CSV   — Chargeback 导出
 *   GET    /budgets                   — 当前租户预算
 *   POST   /budgets                   — 创建预算（GW-4302 冲突 / GW-4306 非法）
 *   PUT    /budgets                   — 更新预算
 *   DELETE /budgets                   — 删除预算（SUSPEND 冷静期撤销入口）
 */
import { http } from '../request';

// Re-export VirtualKey 类型，避免在两个文件重复定义
export type { VirtualKey } from './keys';
import type { VirtualKey } from './keys';

export interface UsageRecord {
  recordId: string;
  tenant: { value: string };
  user: { value: string };
  model: { value: string };
  agentName: string;
  timestamp: string;
  tokensIn: number;
  tokensOut: number;
  cost: number;
  unitPriceIn: number;
  unitPriceOut: number;
}

export interface Budget {
  tenant: { value: string };
  type: 'TOKEN' | 'MONEY';
  dailyLimit: number;
  monthlyLimit: number;
  currentDailyUsed: number;
  currentMonthlyUsed: number;
  alertThreshold: { percent: number };
  alertSent: boolean;
  suspendAction?: 'ALERT' | 'THROTTLE' | 'SUSPEND';
  suspendUntil?: string;
}

export interface BudgetRequest {
  type: 'TOKEN' | 'MONEY';
  dailyLimit: number;
  monthlyLimit: number;
  alertThresholdPct: number;
  suspendAction?: string;
}

function isoQ(params: Record<string, string | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v) q.set(k, v);
  const s = q.toString();
  return s ? `?${s}` : '';
}

export function listCosts(params: { from?: string; to?: string } = {}): Promise<UsageRecord[]> {
  return http.get<UsageRecord[]>(`/admin/billing/costs${isoQ(params)}`);
}

export function totalCost(params: { from: string; to: string }): Promise<number> {
  return http.get<number>(`/admin/billing/costs/total${isoQ(params)}`);
}

export function exportUsage(params: { format: 'CSV'; from?: string; to?: string } = { format: 'CSV' }): Promise<UsageRecord[]> {
  return http.get<UsageRecord[]>(`/admin/billing/usage/export${isoQ(params)}`);
}

export function findBudget(): Promise<Budget | null> {
  return http.get<Budget | null>('/admin/billing/budgets');
}

export function createBudget(body: BudgetRequest): Promise<Budget> {
  return http.post<Budget>('/admin/billing/budgets', body);
}

export function updateBudget(body: BudgetRequest): Promise<Budget> {
  return http.put<Budget>('/admin/billing/budgets', body);
}

export function deleteBudget(): Promise<void> {
  return http.delete<void>('/admin/billing/budgets');
}

/* —— Virtual Keys（Round6：预付费充值流程）—— */

/** 列出所有虚拟 Key（含余额 / 配额） */
export function listVirtualKeys(): Promise<VirtualKey[]> {
  return http.get<VirtualKey[]>('/admin/virtual-keys');
}

/** 创建虚拟 Key —— owner / tenant / label / monthlyQuotaCny */
export function createVirtualKey(body: Partial<VirtualKey>): Promise<VirtualKey> {
  return http.post<VirtualKey>('/admin/virtual-keys', body);
}

/** 撤销虚拟 Key —— MVP 软撤销（status → REVOKED） */
export function revokeVirtualKey(vkId: string): Promise<void> {
  return http.delete<void>(`/admin/virtual-keys/${encodeURIComponent(vkId)}`);
}
