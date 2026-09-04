/**
 * ResponsiveSidebar — 响应式 Sidebar 包装器
 *
 * - 桌面端（≥768px）：直接渲染 Sidebar（内部已含 Sider）
 * - 移动端（<768px）：Sidebar 包在 Drawer 里
 */
import { Drawer } from 'antd';
import { Sidebar } from './Sidebar';
import { useIsMobile } from '../../hooks/useMediaQuery';
import { useEffect } from 'react';

interface ResponsiveSidebarProps {
  collapsed: boolean;
  onCollapse: (v: boolean) => void;
  mobileOpen: boolean;
  onMobileClose: () => void;
}

export function ResponsiveSidebar({
  collapsed,
  onCollapse,
  mobileOpen,
  onMobileClose,
}: ResponsiveSidebarProps) {
  const isMobile = useIsMobile();

  useEffect(() => {
    if (isMobile) onCollapse(false);
  }, [isMobile, onCollapse]);

  if (isMobile) {
    return (
      <Drawer
        open={mobileOpen}
        onClose={onMobileClose}
        placement="left"
        width={240}
        closable={false}
        styles={{
          body: { padding: 0, background: 'var(--brand-deep)' },
          header: { display: 'none' },
        }}
      >
        <Sidebar collapsed={false} onCollapse={() => {}} />
      </Drawer>
    );
  }

  return <Sidebar collapsed={collapsed} onCollapse={onCollapse} />;
}