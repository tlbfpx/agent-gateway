/**
 * CommandPalette — 全局 ⌘K 命令面板
 * - 输入关键字 → 模糊匹配
 * - 5 类来源：导航 / 快速操作 / 模型 / API Key / Agent
 * - 键盘可达：↑↓ 切换 · Enter 触发 · Esc 关闭
 * - 失败容错：API 拿不到时退化为只显示导航
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Modal, Input, Empty, Spin, message } from 'antd';
import {
  DashboardOutlined,
  ClusterOutlined,
  ApiOutlined,
  KeyOutlined,
  AppstoreOutlined,
  RobotOutlined,
  NotificationOutlined,
  AuditOutlined,
  HistoryOutlined,
  SafetyOutlined,
  MessageOutlined,
  SettingOutlined,
  SearchOutlined,
  PlusOutlined,
  ThunderboltOutlined,
  AlertOutlined,
  DollarOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { usePaletteOpen, closePalette } from '../../hooks/useCommandPalette';
import { rankBy } from '../../lib/fuzzy';
import { listModels } from '../../lib/api/models';
import { listApiKeys } from '../../lib/api/keys';
import { listAgents } from '../../lib/api/agents';
import type { Model } from '../../lib/api/models';
import type { ApiKey } from '../../lib/api/keys';
import type { AgentInfo } from '../../lib/api/agents';

interface NavItem {
  id: string;
  title: string;
  path: string;
  icon: React.ReactNode;
  group: '导航';
}

interface CommandItem {
  id: string;
  title: string;
  subtitle?: string;
  path: string;
  icon: React.ReactNode;
  group: '导航' | '快速操作' | '模型' | 'API Key' | 'Agent';
  /** 搜索关键字来源（默认 title） */
  keywords?: string;
  /** 操作动作（设置则执行而非单纯跳转） */
  action?: () => void | Promise<void>;
}

const NAV: NavItem[] = [
  { id: 'n-dashboard', title: '仪表盘', path: '/dashboard', icon: <DashboardOutlined />, group: '导航' },
  { id: 'n-health', title: '健康检查', path: '/health', icon: <ClusterOutlined />, group: '导航' },
  { id: 'n-models', title: '模型管理', path: '/models', icon: <ApiOutlined />, group: '导航' },
  { id: 'n-keys', title: 'API Key', path: '/api-keys', icon: <KeyOutlined />, group: '导航' },
  { id: 'n-discovery', title: '服务发现', path: '/discovery', icon: <AppstoreOutlined />, group: '导航' },
  { id: 'n-agents', title: 'Agent 注册', path: '/agents', icon: <RobotOutlined />, group: '导航' },
  { id: 'n-webhooks', title: 'Webhook', path: '/webhooks', icon: <NotificationOutlined />, group: '导航' },
  { id: 'n-audit', title: '审计日志', path: '/audit', icon: <AuditOutlined />, group: '导航' },
  { id: 'n-config', title: '配置历史', path: '/config-history', icon: <HistoryOutlined />, group: '导航' },
  { id: 'n-rbac', title: 'RBAC', path: '/rbac', icon: <SafetyOutlined />, group: '导航' },
  { id: 'n-chat', title: '对话测试', path: '/chat', icon: <MessageOutlined />, group: '导航' },
  { id: 'n-settings', title: '设置', path: '/settings', icon: <SettingOutlined />, group: '导航' },
];

const groupOrder: CommandItem['group'][] = ['快速操作', '导航', '模型', 'API Key', 'Agent'];
const groupIcons: Record<CommandItem['group'], React.ReactNode> = {
  导航: <DashboardOutlined />,
  快速操作: <ThunderboltOutlined />,
  模型: <ApiOutlined />,
  'API Key': <KeyOutlined />,
  Agent: <RobotOutlined />,
};

export function CommandPalette() {
  const open = usePaletteOpen();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [models, setModels] = useState<Model[]>([]);
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<any>(null);

  // 打开时拉数据 + 聚焦 + 重置
  useEffect(() => {
    if (!open) return;
    setQuery('');
    setActive(0);
    setLoading(true);
    Promise.allSettled([listModels(), listApiKeys(), listAgents()])
      .then(([m, k, a]) => {
        if (m.status === 'fulfilled') setModels(m.value);
        if (k.status === 'fulfilled') setKeys(k.value);
        if (a.status === 'fulfilled') setAgents(a.value);
      })
      .finally(() => setLoading(false));
    // 延迟聚焦以等 modal 渲染
    const t = setTimeout(() => inputRef.current?.focus?.(), 60);
    return () => clearTimeout(t);
  }, [open]);

  const items: CommandItem[] = useMemo(() => {
    const navItems: CommandItem[] = NAV.map((n) => ({
      id: n.id,
      title: n.title,
      path: n.path,
      icon: n.icon,
      group: '导航',
    }));
    const modelItems: CommandItem[] = models.map((m) => ({
      id: `m-${m.id}`,
      title: m.displayName || m.id,
      subtitle: `${m.provider} · ${m.id}`,
      path: `/models?focus=${encodeURIComponent(m.id)}`,
      icon: <ApiOutlined />,
      group: '模型',
      keywords: [m.id, m.displayName, m.provider, m.modelName].filter(Boolean).join(' '),
    }));
    const keyItems: CommandItem[] = keys.map((k) => ({
      id: `k-${k.id}`,
      title: k.id,
      subtitle: `${k.tenant} · ${k.enabled ? '启用' : '已撤销'}`,
      path: `/api-keys?focus=${encodeURIComponent(k.id)}`,
      icon: <KeyOutlined />,
      group: 'API Key',
      keywords: [k.id, k.tenant, k.owner].filter(Boolean).join(' '),
    }));
    const agentItems: CommandItem[] = agents.map((a) => ({
      id: `a-${a.name}`,
      title: a.name,
      subtitle: a.description || a.skills?.join(', ') || '—',
      path: `/agents?focus=${encodeURIComponent(a.name)}`,
      icon: <RobotOutlined />,
      group: 'Agent',
      keywords: [a.name, a.description, ...(a.skills ?? [])].filter(Boolean).join(' '),
    }));
    return [...navItems, ...modelItems, ...keyItems, ...agentItems];
  }, [models, keys, agents]);

  const quickActions: CommandItem[] = useMemo(
    () => [
      {
        id: 'q-new-model',
        title: '新建模型',
        subtitle: '打开新建模型抽屉',
        path: '/models?action=create',
        icon: <PlusOutlined />,
        group: '快速操作',
        keywords: '新建 创建 添加 模型 create add model',
      },
      {
        id: 'q-issue-key',
        title: '签发 API Key',
        subtitle: '为当前租户签发新 Key',
        path: '/api-keys?action=create',
        icon: <KeyOutlined />,
        group: '快速操作',
        keywords: '签发 创建 key api',
      },
      {
        id: 'q-register-agent',
        title: '注册 Agent',
        subtitle: '添加新的 Agent 端点',
        path: '/agents?action=create',
        icon: <RobotOutlined />,
        group: '快速操作',
        keywords: '注册 添加 新建 agent',
      },
      {
        id: 'q-subscribe-webhook',
        title: '订阅 Webhook',
        subtitle: '添加新的事件订阅',
        path: '/webhooks?action=create',
        icon: <NotificationOutlined />,
        group: '快速操作',
        keywords: '订阅 webhook 添加 回调',
      },
      {
        id: 'q-new-alert',
        title: '新建告警规则',
        subtitle: '添加监控规则',
        path: '/alerts?action=create',
        icon: <AlertOutlined />,
        group: '快速操作',
        keywords: '新建 添加 告警 规则 alert rule',
      },
      {
        id: 'q-new-policy',
        title: '新建策略规则',
        subtitle: '添加 RBAC 规则',
        path: '/policies?action=create',
        icon: <SafetyOutlined />,
        group: '快速操作',
        keywords: '新建 添加 策略 规则 policy',
      },
      {
        id: 'q-toggle-theme',
        title: '切换深色 / 浅色',
        subtitle: '在 Settings 中调整',
        path: '/settings',
        icon: <SettingOutlined />,
        group: '快速操作',
        keywords: '切换 主题 深色 浅色 theme dark light',
        action: async () => {
          const html = document.documentElement;
          const cur = html.getAttribute('data-theme') ?? 'light';
          html.setAttribute('data-theme', cur === 'dark' ? 'light' : 'dark');
          message.success(cur === 'dark' ? '已切换到浅色' : '已切换到深色');
        },
      },
      {
        id: 'q-collapse-sidebar',
        title: '折叠 / 展开侧栏',
        subtitle: '切换 Sidebar',
        path: '/',
        icon: <CodeOutlined />,
        group: '快速操作',
        keywords: '折叠 展开 侧栏 sidebar',
        action: async () => {
          // 触发自定义事件给 AppShell
          window.dispatchEvent(new CustomEvent('agent-gateway:toggle-sidebar'));
        },
      },
    ],
    [],
  );

  const filtered = useMemo(() => {
    const all = [...quickActions, ...items];
    if (!query.trim()) return all;
    return rankBy(all, query, (it) => it.keywords ?? it.title).slice(0, 30);
  }, [items, quickActions, query]);

  // 按 group 排序：保持 groupOrder 顺序
  const grouped = useMemo(() => {
    const map: Record<CommandItem['group'], CommandItem[]> = {
      导航: [],
      快速操作: [],
      模型: [],
      'API Key': [],
      Agent: [],
    };
    for (const it of filtered) map[it.group].push(it);
    return groupOrder.map((g) => ({ group: g, items: map[g] })).filter((g) => g.items.length > 0);
  }, [filtered]);

  // 扁平列表（用于 keyboard 导航）
  const flat = useMemo(() => grouped.flatMap((g) => g.items), [grouped]);

  // 选中项变化时 clamp active
  useEffect(() => {
    if (active >= flat.length) setActive(0);
  }, [flat, active]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((a) => (flat.length === 0 ? 0 : (a + 1) % flat.length));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => (flat.length === 0 ? 0 : (a - 1 + flat.length) % flat.length));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const it = flat[active];
      if (it) {
        if (it.action) {
          try {
            Promise.resolve(it.action()).catch(() => {});
          } catch {
            /* ignore */
          }
        }
        if (it.path && it.path !== '/') {
          navigate(it.path);
        }
        closePalette();
      }
    } else if (e.key === 'Escape') {
      closePalette();
    }
  };

  return (
    <Modal
      open={open}
      onCancel={closePalette}
      footer={null}
      closable={false}
      width={640}
      destroyOnHidden
      styles={{
        body: { padding: 0 },
        content: { padding: 0, borderRadius: 12, overflow: 'hidden' },
      }}
      maskClosable
      centered
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 16px', borderBottom: '1px solid var(--border-thin)' }}>
        <SearchOutlined style={{ color: 'var(--text-3)', fontSize: 16 }} />
        <Input
          ref={inputRef}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setActive(0);
          }}
          onKeyDown={onKeyDown}
          placeholder="搜索菜单、模型、API Key、Agent…"
          variant="borderless"
          style={{ padding: 0, fontSize: 15 }}
        />
        <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)', padding: '2px 6px', border: '1px solid var(--border-thin)', borderRadius: 4 }}>
          ESC
        </span>
      </div>

      <div style={{ maxHeight: 440, overflowY: 'auto', padding: '8px 0' }}>
        {loading && (
          <div style={{ padding: 24, textAlign: 'center' }}>
            <Spin size="small" /> <span style={{ marginLeft: 8, color: 'var(--text-3)' }}>加载索引…</span>
          </div>
        )}

        {!loading && flat.length === 0 && (
          <div style={{ padding: 24 }}>
            <Empty description={query ? `没有匹配 "${query}" 的结果` : '输入关键字开始搜索'} />
          </div>
        )}

        {!loading &&
          grouped.map((g) => (
            <div key={g.group}>
              <div
                style={{
                  padding: '8px 16px 4px',
                  fontSize: 11,
                  fontFamily: 'var(--font-mono)',
                  letterSpacing: 1.2,
                  color: 'var(--text-3)',
                  textTransform: 'uppercase',
                }}
              >
                {g.group} · {g.items.length}
              </div>
              {g.items.map((it) => {
                const idx = flat.indexOf(it);
                const isActive = idx === active;
                return (
                  <div
                    key={it.id}
                    onMouseEnter={() => setActive(idx)}
                    onClick={() => {
                      if (it.action) {
                        try {
                          Promise.resolve(it.action()).catch(() => {});
                        } catch {
                          /* ignore */
                        }
                      }
                      if (it.path && it.path !== '/') {
                        navigate(it.path);
                      }
                      closePalette();
                    }}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      padding: '10px 16px',
                      cursor: 'pointer',
                      background: isActive ? 'var(--brand-amber-soft)' : 'transparent',
                      borderLeft: `3px solid ${isActive ? 'var(--brand-amber)' : 'transparent'}`,
                    }}
                  >
                    <span style={{ color: isActive ? 'var(--brand-amber)' : 'var(--text-3)', width: 18, textAlign: 'center' }}>
                      {it.icon}
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 13, color: 'var(--text-1)', fontWeight: isActive ? 500 : 400, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {it.title}
                      </div>
                      {it.subtitle && (
                        <div style={{ fontSize: 11, color: 'var(--text-3)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {it.subtitle}
                        </div>
                      )}
                    </div>
                    {isActive && (
                      <span className="mono" style={{ fontSize: 10, color: 'var(--text-3)' }}>↵</span>
                    )}
                  </div>
                );
              })}
            </div>
          ))}
      </div>

      <div
        style={{
          padding: '8px 16px',
          borderTop: '1px solid var(--border-thin)',
          display: 'flex',
          gap: 16,
          fontSize: 11,
          color: 'var(--text-3)',
          background: 'var(--bg-sunken)',
        }}
      >
        <span><kbd className="mono" style={{ padding: '0 4px', border: '1px solid var(--border-thin)', borderRadius: 3 }}>↑↓</kbd> 切换</span>
        <span><kbd className="mono" style={{ padding: '0 4px', border: '1px solid var(--border-thin)', borderRadius: 3 }}>↵</kbd> 选择</span>
        <span><kbd className="mono" style={{ padding: '0 4px', border: '1px solid var(--border-thin)', borderRadius: 3 }}>ESC</kbd> 关闭</span>
        <span style={{ flex: 1 }} />
        <span>{flat.length} 个结果</span>
      </div>
    </Modal>
  );
}
