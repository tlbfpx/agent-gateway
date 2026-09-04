/**
 * NotificationCenter — Header 通知中心
 *
 * - 铃铛 icon + 未读数 Badge
 * - 点击展开 Popover：未读 / 全部 / 已读三栏
 * - 单条标记已读 + 跳转链接 + 删除
 * - 一键全部已读 + 清空
 *
 * 数据流（Round4 运营评审 #18）：
 *   firing 告警通过 useAlertNotifications 自动 push → 通知中心
 *   Webhook 死信 30s 轮询 → 首次出现的死信转 critical 通知
 *   角标口径（Round5 修复回归一）：只反映 unread 通知数，
 *     与「未读 N」Tab 标签 + 「N 未读」Tag 完全一致；
 *     firing 告警数由面板内独立的「N 告警触发中」按钮单独承载，
 *     不再把两个不同域的数混算进同一个 count（firing=5/unread=0 会
 *     让运营误判为通知中心故障）。
 */
import { useEffect, useState } from 'react';
import { Popover, Button, Badge, Tabs, Empty, Tooltip, Tag, Space } from 'antd';
import {
  BellOutlined,
  CheckOutlined,
  DeleteOutlined,
  ClearOutlined,
  ClockCircleOutlined,
  AlertOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useNotifications, type Notification, type NotificationLevel } from '../../hooks/useNotifications';
import { useAlertNotifications } from '../../hooks/useAlertNotifications';
import { alertsApi } from '../../lib/api/alerts';
import { listDeadLetters } from '../../lib/api/webhooks';
import { addNotification } from '../../hooks/useNotifications';
import { EmptyState } from './EmptyState';

/**
 * Round5 修复（回归二：死信重复 push）：
 * 原本在 useEffect 内部 new Set()，每次组件挂载都重置；又因
 * useNotifications 的 dedupKey 拿内存 _list 比对且上限 100 ——
 * 用户清空全部 / 旧条目被挤出后，重新挂载会重灌历史死信，淹没清理结果。
 *
 * 提升到模块作用域 + 持久化 localStorage：整页刷新也生效。
 * key 组成 `${url}::${event}::${lastTryAt ?? ''}` 与 addNotification.dedupKey
 * 完全一致 —— lastTryAt 变化产生新 key，是期望行为（重试失败再次告知）。
 */
const DLQ_PUSHED_KEY = 'agent-gateway.dlq-pushed';
const DLQ_PUSHED_MAX = 500;

function readPushedDlq(): Set<string> {
  try {
    const raw = localStorage.getItem(DLQ_PUSHED_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return new Set();
    return new Set(arr.slice(-DLQ_PUSHED_MAX));
  } catch {
    return new Set();
  }
}

function writePushedDlq(set: Set<string>) {
  try {
    const arr = Array.from(set).slice(-DLQ_PUSHED_MAX);
    localStorage.setItem(DLQ_PUSHED_KEY, JSON.stringify(arr));
  } catch {
    /* silent */
  }
}

const pushedDlqKeys = readPushedDlq();

const LEVEL_ICON: Record<NotificationLevel, string> = {
  info: 'ℹ',
  warning: '⚠',
  critical: '⚠',
};

const LEVEL_COLOR: Record<NotificationLevel, string> = {
  info: 'var(--ant-primary)',
  warning: 'var(--ant-warning)',
  critical: 'var(--ant-error)',
};

const SOURCE_LABEL: Record<Notification['source'], string> = {
  system: '系统',
  alert: '告警',
  mention: '@我',
};

export function NotificationCenter() {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<'unread' | 'all' | 'read'>('unread');
  const { list, unreadCount, markRead, markAllRead, clear, remove } = useNotifications();
  const navigate = useNavigate();
  // firing 告警联动(spec 2026-08-19 §6.3):60s 轮询,严重告警优先展示在角标
  const [firingCount, setFiringCount] = useState(0);

  // Round4：单一数据源（single source of truth）
  // 1) firing 告警自动 push 到通知中心（去重 by alertId）
  useAlertNotifications();
  // 2) firing 计数用于角标叠加（保留独立状态）
  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const records = await alertsApi.records('firing');
        if (!cancelled) setFiringCount(records.length);
      } catch {
        if (!cancelled) setFiringCount(0);
      }
    };
    void load();
    const t = setInterval(load, 60_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, []);

  // Round5 修复：seen 集合提升到模块作用域 + 持久化到 localStorage。
  // 每次 sync 只读取 pushedDlqKeys，不重置；新增的 key 写回 localStorage。
  useEffect(() => {
    let cancelled = false;
    const sync = async () => {
      try {
        const list = await listDeadLetters();
        if (cancelled) return;
        let dirty = false;
        for (const d of list) {
          const key = `${d.url}::${d.event}::${d.lastTryAt ?? ''}`;
          if (pushedDlqKeys.has(key)) continue;
          pushedDlqKeys.add(key);
          dirty = true;
          addNotification({
            level: 'critical',
            title: `Webhook 死信：${d.event}`,
            description: `${d.url} · ${d.attempts} 次失败：${d.error}`,
            link: '/webhooks',
            source: 'system',
            dedupKey: `dlq:${key}`,
          });
        }
        if (dirty) writePushedDlq(pushedDlqKeys);
      } catch {
        // 后端未配置 / 网络失败 → 不打扰
      }
    };
    void sync();
    const t = setInterval(sync, 30_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, []);

  const filtered = list.filter((n) => {
    if (tab === 'unread') return !n.read;
    if (tab === 'read') return n.read;
    return true;
  });

  const onClick = (n: Notification) => {
    if (!n.read) markRead(n.id);
    if (n.link) {
      navigate(n.link);
      setOpen(false);
    }
  };

  const content = (
    <div style={{ width: 360, padding: 0 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '12px 16px',
          borderBottom: '1px solid var(--border-thin)',
          background: 'var(--bg-sunken)',
        }}
      >
        <strong style={{ fontSize: 13 }}>通知中心</strong>
        <Tag color={unreadCount > 0 ? 'error' : 'default'} style={{ margin: 0 }}>
          {unreadCount} 未读
        </Tag>
        <div style={{ flex: 1 }} />
        {firingCount > 0 && (
          <Tooltip title="查看告警中心">
            <Button
              size="small"
              danger
              type="text"
              icon={<AlertOutlined />}
              onClick={() => {
                navigate('/alerts');
                setOpen(false);
              }}
            >
              {firingCount} 告警触发中
            </Button>
          </Tooltip>
        )}
        <Tooltip title="全部标为已读">
          <Button
            type="text"
            size="small"
            icon={<CheckOutlined />}
            disabled={unreadCount === 0}
            onClick={markAllRead}
          />
        </Tooltip>
        <Tooltip title="清空全部">
          <Button
            type="text"
            size="small"
            danger
            icon={<ClearOutlined />}
            disabled={list.length === 0}
            onClick={clear}
          />
        </Tooltip>
      </div>

      <Tabs
        activeKey={tab}
        onChange={(k) => setTab(k as typeof tab)}
        size="small"
        style={{ padding: '0 12px' }}
        items={[
          { key: 'unread', label: `未读 ${unreadCount}` },
          { key: 'all', label: `全部 ${list.length}` },
          { key: 'read', label: `已读 ${list.filter((n) => n.read).length}` },
        ]}
      />

      <div style={{ maxHeight: 380, overflowY: 'auto' }}>
        {filtered.length === 0 ? (
          <div style={{ padding: 24 }}>
            <EmptyState variant="no-data" description={tab === 'unread' ? '无未读通知' : '暂无通知'} />
          </div>
        ) : (
          filtered.map((n) => (
            <div
              key={n.id}
              onClick={() => onClick(n)}
              style={{
                padding: '10px 16px',
                borderBottom: '1px solid var(--border-thin)',
                cursor: n.link ? 'pointer' : 'default',
                background: n.read ? 'transparent' : 'var(--bg-sunken)',
                opacity: n.read ? 0.7 : 1,
                position: 'relative',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                <span
                  style={{
                    color: LEVEL_COLOR[n.level],
                    fontSize: 14,
                    flexShrink: 0,
                    marginTop: 1,
                  }}
                >
                  {LEVEL_ICON[n.level]}
                </span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div
                    style={{
                      fontSize: 13,
                      fontWeight: n.read ? 400 : 500,
                      color: 'var(--text-1)',
                      marginBottom: 2,
                    }}
                  >
                    {n.title}
                  </div>
                  <div
                    style={{
                      fontSize: 12,
                      color: 'var(--text-3)',
                      marginBottom: 4,
                      lineHeight: 1.5,
                    }}
                  >
                    {n.description}
                  </div>
                  <Space size={6}>
                    <span
                      className="mono"
                      style={{
                        fontSize: 10,
                        color: 'var(--text-3)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 3,
                      }}
                    >
                      <ClockCircleOutlined style={{ fontSize: 9 }} />
                      {n.time.slice(0, 16)}
                    </span>
                    <Tag style={{ margin: 0, fontSize: 10 }}>{SOURCE_LABEL[n.source]}</Tag>
                  </Space>
                </div>
                <Tooltip title="删除">
                  <Button
                    type="text"
                    size="small"
                    icon={<DeleteOutlined />}
                    onClick={(e) => {
                      e.stopPropagation();
                      remove(n.id);
                    }}
                    aria-label="删除通知"
                  />
                </Tooltip>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      arrow={false}
    >
      <Badge count={unreadCount} size="small" offset={[-2, 4]}>
        <Tooltip title="通知中心">
          <button
            type="button"
            aria-label="通知中心"
            style={{
              background: 'transparent',
              border: 'none',
              width: 32,
              height: 32,
              display: 'grid',
              placeItems: 'center',
              borderRadius: 'var(--r-md)',
              color: 'var(--text-2)',
              cursor: 'pointer',
            }}
          >
            <BellOutlined />
          </button>
        </Tooltip>
      </Badge>
    </Popover>
  );
}