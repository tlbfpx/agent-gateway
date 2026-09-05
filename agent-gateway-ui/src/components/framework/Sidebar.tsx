import { Layout as AntLayout, Menu, Badge } from 'antd';
import type { MenuProps } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  DashboardOutlined,
  ApiOutlined,
  KeyOutlined,
  RobotOutlined,
  ClusterOutlined,
  NotificationOutlined,
  AuditOutlined,
  HistoryOutlined,
  SafetyOutlined,
  MessageOutlined,
  SettingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  AppstoreOutlined,
  DollarOutlined,
  CodeOutlined,
  ThunderboltOutlined,
  AlertOutlined,
  NodeIndexOutlined,
  BranchesOutlined,
  ReloadOutlined,
  RocketOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useSidebarCounts, getCount, isCritical } from '../../hooks/useSidebarCounts';
import { useRole, canAccessRoute } from '../../hooks/useRole';

const { Sider } = AntLayout;

type MenuItem = Required<MenuProps>['items'][number];

/** 菜单项与 SidebarCounts 的映射 */
const COUNT_KEY: Record<string, 'models' | 'apiKeys' | 'agents' | 'webhooks' | 'alerts' | 'ratelimit' | 'chat' | undefined> = {
  '/models': 'models',
  '/api-keys': 'apiKeys',
  '/agents': 'agents',
  '/webhooks': 'webhooks',
  '/alerts': 'alerts',
  '/ratelimit': 'ratelimit',
  '/chat': 'chat',
};

interface NavDef {
  key: string;
  icon: React.ReactNode;
  label: string;
}

const GROUPS: { title: string; items: NavDef[] }[] = [
  {
    title: '总览',
    items: [
      { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
      { key: '/health', icon: <ClusterOutlined />, label: '健康检查' },
    ],
  },
  {
    title: '资源管理',
    items: [
      { key: '/models', icon: <ApiOutlined />, label: '模型管理' },
      { key: '/api-keys', icon: <KeyOutlined />, label: 'API Key' },
      { key: '/discovery', icon: <AppstoreOutlined />, label: '服务发现' },
      { key: '/agents', icon: <RobotOutlined />, label: 'Agent 注册' },
    ],
  },
  {
    title: '运营',
    items: [
      { key: '/cost', icon: <DollarOutlined />, label: '成本中心' },
      { key: '/budgets', icon: <DollarOutlined />, label: '预算管理' },
      { key: '/ratelimit', icon: <ThunderboltOutlined />, label: '限流监控' },
      { key: '/traces', icon: <NodeIndexOutlined />, label: '调用链追踪' },
      { key: '/workflows', icon: <BranchesOutlined />, label: 'Workflow' },
      { key: '/alerts', icon: <AlertOutlined />, label: '告警中心' },
      { key: '/webhooks', icon: <NotificationOutlined />, label: 'Webhook' },
      { key: '/audit', icon: <AuditOutlined />, label: '审计日志' },
      { key: '/feedback', icon: <MessageOutlined />, label: 'Feedback' },
      { key: '/admin-users', icon: <AuditOutlined />, label: 'Admin 用户' },
      { key: '/teams', icon: <ClusterOutlined />, label: '团队管理' },
      { key: '/prompts', icon: <ExperimentOutlined />, label: 'Prompt 模板' },
      { key: '/datasets', icon: <ExperimentOutlined />, label: '数据集 / 评测' },
      { key: '/mcp', icon: <ApiOutlined />, label: 'MCP 协议' },
      { key: '/k8s', icon: <ExperimentOutlined />, label: 'K8s CRD' },
      { key: '/plugins', icon: <ExperimentOutlined />, label: '插件系统' },
      { key: '/config-history', icon: <HistoryOutlined />, label: '配置历史' },
      { key: '/config-reloader', icon: <ReloadOutlined />, label: '配置重载' },
      { key: '/cache', icon: <ThunderboltOutlined />, label: '语义缓存' },
      { key: '/guardrails', icon: <SafetyOutlined />, label: 'Guardrails' },
      { key: '/rbac', icon: <SafetyOutlined />, label: '权限预览' },
      { key: '/roles', icon: <SafetyOutlined />, label: '角色管理' },
      { key: '/user-bindings', icon: <SafetyOutlined />, label: '用户绑定' },
      { key: '/policies', icon: <SafetyOutlined />, label: '策略中心' },
    ],
  },
  {
    title: '开发者',
    items: [{ key: '/api', icon: <CodeOutlined />, label: 'API 浏览器' }],
  },
  {
    title: '应用',
    items: [
      { key: '/chat', icon: <MessageOutlined />, label: '对话测试' },
      { key: '/help', icon: <CodeOutlined />, label: '帮助' },
      { key: '/settings', icon: <SettingOutlined />, label: '设置' },
    ],
  },
  {
    title: '项目',
    items: [
      { key: '/getting-started', icon: <RocketOutlined />, label: '快速上手' },
      { key: '/changelog', icon: <HistoryOutlined />, label: '更新日志' },
      { key: '/pricing', icon: <DollarOutlined />, label: '定价' },
      { key: '/status', icon: <ClusterOutlined />, label: '系统状态' },
      { key: '/contact', icon: <MessageOutlined />, label: '联系我们' },
    ],
  },
];

interface SidebarProps {
  collapsed: boolean;
  onCollapse: (v: boolean) => void;
}

export function Sidebar({ collapsed, onCollapse }: SidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const { counts } = useSidebarCounts();
  const role = useRole();

  // 按角色过滤
  const visibleGroups = GROUPS.map((g) => ({
    title: g.title,
    items: g.items.filter((it) => canAccessRoute(role, it.key)),
  })).filter((g) => g.items.length > 0);

  // 给菜单项追加 count badge（仅展开状态可见）
  const items: MenuItem[] = visibleGroups.flatMap((g) => [
    {
      key: `__group_${g.title}`,
      type: 'group' as const,
      label: !collapsed ? g.title : '',
      children: g.items.map((it) => {
        const countKey = COUNT_KEY[it.key];
        const cnt = countKey ? getCount(counts, countKey) : null;
        const crit = countKey ? isCritical(countKey, counts) : false;
        if (!countKey || cnt == null) {
          return { key: it.key, icon: it.icon, label: it.label };
        }
        return {
          key: it.key,
          icon: it.icon,
          label: (
            <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
              <span>{it.label}</span>
              <Badge
                count={cnt}
                overflowCount={999}
                style={{
                  // 在深蓝侧栏上，浅色半透明徽章易"消失"；
                  // 给非关键徽章一个柔和的琥珀色填充 + 白色文字，保证可读性
                  background: crit ? 'var(--ant-error)' : 'rgba(212, 165, 116, .22)',
                  color: crit ? '#fff' : 'var(--brand-amber)',
                  fontSize: 10,
                  fontWeight: 600,
                  boxShadow: 'none',
                  padding: '0 6px',
                  minWidth: 18,
                  height: 16,
                  lineHeight: '16px',
                  border: crit ? 'none' : '1px solid rgba(212, 165, 116, .35)',
                }}
              />
            </span>
          ),
        };
      }),
    },
  ]);

  return (
    <Sider
      width={240}
      collapsedWidth={64}
      collapsed={collapsed}
      onCollapse={onCollapse}
      trigger={null}
      style={{ position: 'relative' }}
    >
      <div className="sb-brand">
        <div className="sb-logo">AG</div>
        {!collapsed && (
          <div className="sb-title-text">
            Agent Gateway
            <small>ADMIN CONSOLE</small>
          </div>
        )}
      </div>

      <Menu
        mode="inline"
        theme="dark"
        className="sb-menu"
        selectedKeys={[location.pathname]}
        onClick={({ key }) => navigate(key as string)}
        style={{ flex: 1, borderInlineEnd: 0 }}
        inlineIndent={20}
        items={items}
      />

      <div className="sb-footer">
        <button
          type="button"
          onClick={() => onCollapse(!collapsed)}
          aria-label={collapsed ? '展开侧栏' : '折叠侧栏'}
          style={{
            background: 'transparent',
            border: 'none',
            color: 'rgba(232, 236, 247, .78)',
            cursor: 'pointer',
            padding: 4,
            display: 'flex',
            alignItems: 'center',
            transition: 'color .15s',
          }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.color = '#fff'; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.color = 'rgba(232, 236, 247, .78)'; }}
        >
          {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
        </button>
        {!collapsed && <span className="version">v0.6.0</span>}
      </div>
    </Sider>
  );
}