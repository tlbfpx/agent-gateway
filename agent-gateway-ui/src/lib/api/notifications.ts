/**
 * notifications.ts — 通知中心数据层
 *
 * 三类通知：
 *  1. system      系统消息（维护、版本更新）
 *  2. alert       告警事件（来自 AlertCenter）
 *  3. activity    用户/审计相关动作
 *
 * 真实接口（待 bootstrap 接线）：
 *   GET    /v1/notifications              → Notification[]
 *   POST   /v1/notifications/:id/read     → { ok }
 *   POST   /v1/notifications/read-all     → { ok, count }
 *
 * 降级策略：后台拉取告警事件 + 系统消息 → 聚合成本地
 */
import { http } from '../request';

export type NotificationKind = 'system' | 'alert' | 'activity';

export interface Notification {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  time: string;
  read: boolean;
  /** 关联跳转路径 */
  link?: string;
  /** 关联严重度（仅 alert） */
  severity?: 'info' | 'warning' | 'critical';
}

export interface NotificationsReport {
  notifications: Notification[];
  unread: number;
}

const SYSTEM_SEED: Notification[] = [
  {
    id: 'n-sys-001',
    kind: 'system',
    title: '网关 v0.6.0 发布',
    body: '新增策略中心、限流可视化、告警中心；模型灰度支持计划切换。',
    time: new Date(Date.now() - 30 * 60_000).toISOString(),
    read: false,
    link: '/alerts',
  },
  {
    id: 'n-sys-002',
    kind: 'system',
    title: '维护窗口预告',
    body: '本周日 02:00–03:00 (UTC+8) 数据库版本升级，预计 5 分钟不可用。',
    time: new Date(Date.now() - 4 * 3600_000).toISOString(),
    read: false,
  },
];

const ACTIVITY_SEED: Notification[] = [
  {
    id: 'n-act-001',
    kind: 'activity',
    title: 'admin 签发了新 API Key',
    body: 'tenant-b · pk_live_xxx · 5 分钟前',
    time: new Date(Date.now() - 5 * 60_000).toISOString(),
    read: false,
    link: '/api-keys',
  },
  {
    id: 'n-act-002',
    kind: 'activity',
    title: 'ops 删除了模型 claude-haiku',
    body: '已影响路由 · 请评估',
    time: new Date(Date.now() - 60 * 60_000).toISOString(),
    read: true,
    link: '/models',
  },
];

async function fetchLive(): Promise<Notification[] | null> {
  try {
    return await http.get<Notification[]>('/notifications');
  } catch {
    return null;
  }
}

async function fetchAlertEvents(): Promise<Notification[]> {
  try {
    const { alertsApi } = await import('./alerts');
    const events = await alertsApi.events();
    return events.map((e) => ({
      id: `alert-${e.id}`,
      kind: 'alert' as const,
      title: e.ruleName,
      body: e.message,
      time: e.time,
      read: e.acknowledged,
      severity: e.severity,
      link: '/alerts',
    }));
  } catch {
    return [];
  }
}

export async function loadNotifications(): Promise<NotificationsReport> {
  const [live, alerts] = await Promise.allSettled([fetchLive(), fetchAlertEvents()]);
  if (live.status === 'fulfilled' && live.value != null) {
    const list = live.value;
    return {
      notifications: list,
      unread: list.filter((n) => !n.read).length,
    };
  }
  const list = [...SYSTEM_SEED, ...alerts.status === 'fulfilled' ? alerts.value : [], ...ACTIVITY_SEED].sort(
    (a, b) => new Date(b.time).getTime() - new Date(a.time).getTime(),
  );
  return {
    notifications: list,
    unread: list.filter((n) => !n.read).length,
  };
}

export async function markRead(id: string): Promise<void> {
  try {
    await http.post(`/notifications/${encodeURIComponent(id)}/read`, {});
  } catch {
    /* local cache 更新即可 */
  }
}

export async function markAllRead(): Promise<void> {
  try {
    await http.post('/notifications/read-all', {});
  } catch {
    /* silent */
  }
}

export const KIND_LABEL: Record<NotificationKind, string> = {
  system: '系统',
  alert: '告警',
  activity: '动态',
};

export const KIND_COLOR: Record<NotificationKind, string> = {
  system: 'blue',
  alert: 'error',
  activity: 'default',
};