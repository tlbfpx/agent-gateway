/**
 * useNotifications — 通知中心数据层
 *
 * 通知来源（合并展示）：
 *  1. 系统通知：限流触发 / Agent 离线 / 部署完成
 *  2. 告警事件：从 AlertCenter 同步（critical 优先）— useAlertNotifications 桥接
 *  3. Webhook 死信：每 30s 拉 /admin/webhooks/dead-letters，新增转 critical
 *  4. 用户行为：审批 / @mention
 *
 * 持久化：localStorage 'agent-gateway.notifications'
 * 降级：本地 mock 3-5 条演示数据
 */
import { useEffect, useState, useCallback } from 'react';

export type NotificationLevel = 'info' | 'warning' | 'critical';

export interface Notification {
  id: string;
  level: NotificationLevel;
  title: string;
  description: string;
  time: string;
  read: boolean;
  /** 关联路由，点击可跳转 */
  link?: string;
  /** 来源 */
  source: 'system' | 'alert' | 'mention';
  /** 反向追溯的 alertId / deadLetterKey，避免重复 push（可选） */
  dedupKey?: string;
}

const KEY = 'agent-gateway.notifications';

const SEED: Notification[] = [
  {
    id: 'n-001',
    level: 'critical',
    title: 'Agent weather-mcp 离线',
    description: '已离线 7 分钟，请检查健康状态',
    time: new Date(Date.now() - 12 * 60_000).toISOString(),
    read: false,
    link: '/agents',
    source: 'system',
  },
  {
    id: 'n-002',
    level: 'critical',
    title: '错误率超过 5%',
    description: '5 分钟窗口错误率 8.2% > 5% 阈值',
    time: new Date(Date.now() - 30 * 60_000).toISOString(),
    read: false,
    link: '/alerts',
    source: 'alert',
  },
  {
    id: 'n-003',
    level: 'warning',
    title: 'API Key pk_live_xxx 即将过期',
    description: '将于 3 天后过期，请及时续签',
    time: new Date(Date.now() - 2 * 3600_000).toISOString(),
    read: false,
    link: '/api-keys',
    source: 'system',
  },
  {
    id: 'n-004',
    level: 'info',
    title: '配置已发布',
    description: 'models 配置 v8 已成功发布到 Nacos',
    time: new Date(Date.now() - 6 * 3600_000).toISOString(),
    read: true,
    link: '/config-history',
    source: 'system',
  },
  {
    id: 'n-005',
    level: 'info',
    title: 'Webhook 订阅已添加',
    description: 'orders-svc 已订阅 chat.completed 事件',
    time: new Date(Date.now() - 24 * 3600_000).toISOString(),
    read: true,
    link: '/webhooks',
    source: 'system',
  },
];

function read(): Notification[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return SEED;
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return SEED;
    return parsed;
  } catch {
    return SEED;
  }
}

function write(list: Notification[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* silent */
  }
}

let _list: Notification[] = read();
const _subs = new Set<() => void>();

function commit() {
  write(_list);
  _subs.forEach((fn) => fn());
}

/**
 * 把通知写入全局列表（对外暴露）。
 * - 同 dedupKey 已存在 → 跳过（避免重复 push）
 * - 时间戳自动 now
 * - 自动通知所有订阅者
 */
export function addNotification(
  n: Omit<Notification, 'id' | 'time' | 'read'> & Partial<Pick<Notification, 'id' | 'time' | 'read'>>,
): void {
  const id = n.id ?? `n-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const time = n.time ?? new Date().toISOString();
  const dedupKey = n.dedupKey;
  if (dedupKey && _list.some((x) => x.dedupKey === dedupKey)) return;
  _list = [{ ...n, id, time, read: n.read ?? false }, ..._list];
  // 列表上限 100，避免本地存储爆掉
  if (_list.length > 100) _list = _list.slice(0, 100);
  commit();
}

export function useNotifications() {
  const [list, setList] = useState<Notification[]>(_list);

  useEffect(() => {
    const fn = () => setList([..._list]);
    _subs.add(fn);
    return () => {
      _subs.delete(fn);
    };
  }, []);

  const markRead = useCallback((id: string) => {
    _list = _list.map((n) => (n.id === id ? { ...n, read: true } : n));
    commit();
  }, []);

  const markAllRead = useCallback(() => {
    _list = _list.map((n) => ({ ...n, read: true }));
    commit();
  }, []);

  const clear = useCallback(() => {
    _list = [];
    commit();
  }, []);

  const remove = useCallback((id: string) => {
    _list = _list.filter((n) => n.id !== id);
    commit();
  }, []);

  const unreadCount = list.filter((n) => !n.read).length;

  return { list, unreadCount, markRead, markAllRead, clear, remove, addNotification };
}