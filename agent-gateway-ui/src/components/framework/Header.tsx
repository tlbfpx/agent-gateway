import { Layout as AntLayout, Breadcrumb, Input, Dropdown, Avatar, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import {
  SearchOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
  QuestionCircleOutlined,
  MenuOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate, Link } from 'react-router-dom';
import { clearAuth } from '../../lib/request';
import { openPalette } from '../../hooks/useCommandPalette';
import { DisplaySwitcher } from './DisplaySwitcher';
import { TenantSwitcher } from './TenantSwitcher';
import { NotificationCenter } from './NotificationCenter';

const { Header: AntHeader } = AntLayout;

interface HeaderProps {
  isMobile?: boolean;
  onToggleMobileMenu?: () => void;
}

/** Path 段 → 中文面包屑（覆盖全部 18 个路由） */
const ROUTE_LABELS: Record<string, string> = {
  dashboard: '仪表盘',
  models: '模型管理',
  'api-keys': 'API Key',
  discovery: '服务发现',
  agents: 'Agent 注册',
  webhooks: 'Webhook',
  audit: '审计日志',
  'config-history': '配置历史',
  rbac: 'RBAC',
  policies: '策略中心',
  cost: '成本中心',
  ratelimit: '限流监控',
  alerts: '告警中心',
  api: 'API 浏览器',
  chat: '对话测试',
  settings: '设置',
  help: '帮助',
  health: '健康检查',
};

/** Path → 面包屑映射 */
function pathToCrumbs(pathname: string): { label: string; to?: string }[] {
  if (pathname === '/' || pathname === '/dashboard') {
    return [{ label: '总览' }, { label: '仪表盘' }];
  }
  const segs = pathname.split('/').filter(Boolean);
  const crumbs: { label: string; to?: string }[] = [{ label: '总览', to: '/dashboard' }];
  let acc = '';
  for (const seg of segs) {
    acc += '/' + seg;
    const isLast = acc === pathname;
    crumbs.push({
      label: ROUTE_LABELS[seg] ?? seg,
      to: isLast ? undefined : acc,
    });
  }
  return crumbs;
}

export function Header({ isMobile, onToggleMobileMenu }: HeaderProps = {}) {
  const location = useLocation();
  const navigate = useNavigate();
  const crumbs = pathToCrumbs(location.pathname);

  const userMenuItems: MenuProps['items'] = [
    { key: 'settings', icon: <SettingOutlined />, label: '设置', onClick: () => navigate('/settings') },
    { key: 'help', icon: <QuestionCircleOutlined />, label: '帮助 / 快捷键', onClick: () => navigate('/help') },
    { type: 'divider' },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => {
        clearAuth();
        navigate('/settings');
      },
    },
  ];

  return (
    <AntHeader>
      <div className="header-inner">
        {isMobile && (
          <Tooltip title="菜单">
            <button
              type="button"
              className="header-icon-btn header-menu-btn"
              onClick={onToggleMobileMenu}
              aria-label="打开菜单"
            >
              <MenuOutlined />
            </button>
          </Tooltip>
        )}

        <Breadcrumb
          items={crumbs.map((c, i) => ({
            title: c.to ? <Link to={c.to}>{c.label}</Link> : c.label,
            key: String(i),
          }))}
          style={{ fontSize: 13, whiteSpace: 'nowrap' }}
        />

        {/* 搜索触发器：移动端退化为图标按钮（⌘K 无键盘场景） */}
        {!isMobile ? (
          <button
            type="button"
            className="header-search-btn"
            onClick={openPalette}
            aria-label="打开全局搜索"
          >
            <SearchOutlined style={{ color: 'var(--text-3)', fontSize: 14 }} />
            <span style={{ flex: 1, textAlign: 'left', fontSize: 13, color: 'var(--text-3)' }}>
              搜索模型、API Key、Agent…
            </span>
            <span className="header-search-kbd">⌘ K</span>
          </button>
        ) : (
          <Tooltip title="搜索">
            <button
              type="button"
              className="header-icon-btn"
              onClick={openPalette}
              aria-label="打开全局搜索"
            >
              <SearchOutlined />
            </button>
          </Tooltip>
        )}

        <div className="header-spacer" />

        <NotificationCenter />
        <TenantSwitcher />
        <DisplaySwitcher />

        <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" trigger={['click']}>
          <div className="header-user">
            <Avatar size={28} style={{ background: 'linear-gradient(135deg, #0F1B3D 0%, #1677ff 100%)' }}>
              A
            </Avatar>
            <div style={{ lineHeight: 1.2 }} className="header-user-text">
              <div style={{ fontSize: 13, color: 'var(--text-1)', fontWeight: 500 }}>admin</div>
              <div style={{ fontSize: 11, color: 'var(--text-3)' }}>超级管理员</div>
            </div>
            <UserOutlined style={{ fontSize: 12, color: 'var(--text-3)' }} />
          </div>
        </Dropdown>
      </div>
    </AntHeader>
  );
}
