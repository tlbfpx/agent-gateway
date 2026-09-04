/**
 * useAlertNotifications — 把 firing 告警自动 push 到通知中心
 *
 * 复用 NotificationCenter.tsx 60s 轮询逻辑（运营评审 #18）。
 * - 每 60s 拉一次 /admin/alerts?state=firing
 * - 对每个 firing 记录 push 一条 source='alert' 的未读 Notification
 * - 同 dedupKey（alertId）已存在则跳过；告警 resolved 时下一轮自动清出
 *
 * 此 hook 不维护任何本地状态，纯副作用：让 addNotification 去做。
 * 因此同一应用实例内多次挂载也只会有一条同 alertId 的通知。
 */
import { useEffect } from 'react';
import { alertsApi } from '../lib/api/alerts';
import type { AlertRecord } from '../lib/api/alerts';
import { addNotification, type NotificationLevel } from './useNotifications';

const POLL_MS = 60_000;

function severityToLevel(s: AlertRecord['severity']): NotificationLevel {
  if (s === 'critical') return 'critical';
  if (s === 'warning') return 'warning';
  return 'info';
}

/** 把 firing 告警转成 Notification payload；调用方负责 push */
export function alertToNotification(r: AlertRecord) {
  const ruleName = r.labels?.rule ?? r.ruleId ?? '告警';
  const value = r.observedValue != null ? String(r.observedValue) : '?';
  const threshold = r.threshold != null ? String(r.threshold) : '?';
  return {
    level: severityToLevel(r.severity),
    title: `${ruleName} 触发 (${r.severity})`,
    description: `${value} > ${threshold} · ${r.triggerCount} 次触发`,
    link: `/alerts?id=${encodeURIComponent(r.id)}`,
    source: 'alert' as const,
    dedupKey: `alert:${r.id}`,
  };
}

export function useAlertNotifications(enabled = true) {
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    const sync = async () => {
      try {
        const records = await alertsApi.records('firing');
        if (cancelled) return;
        for (const r of records) {
          addNotification(alertToNotification(r));
        }
      } catch {
        // 503（未配置持久化）/ 网络失败 → 不打扰用户
      }
    };
    void sync();
    const t = setInterval(sync, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [enabled]);
}