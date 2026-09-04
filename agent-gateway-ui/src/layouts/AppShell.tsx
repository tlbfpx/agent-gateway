import { Layout as AntLayout } from 'antd';
import { Outlet } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { ResponsiveSidebar } from '../components/framework/ResponsiveSidebar';
import { Header } from '../components/framework/Header';
import { CommandPalette } from '../components/framework/CommandPalette';
import { ShortcutOverlay } from '../components/framework/ShortcutOverlay';
import { Onboarding } from '../components/framework/Onboarding';
import { ErrorBoundary } from '../components/framework/ErrorBoundary';
import { useGlobalPaletteShortcut } from '../hooks/useCommandPalette';
import { useGlobalShortcuts } from '../hooks/useGlobalShortcuts';
import { applyDisplayPrefsOnce } from '../hooks/useDisplayPrefs';
import { LoadingOverlay } from '../hooks/useLoadingOverlay';
import { useIsMobile } from '../hooks/useMediaQuery';
import { enableCrossTabSync } from '../hooks/useEventBus';

const { Content } = AntLayout;

/**
 * AppShell — 全局三段式壳
 * - 桌面端：左侧 Sidebar（可折叠）+ 顶部 Header
 * - 移动端：Sidebar 变 Drawer 抽屉 + Hamburger 按钮
 * - 全局 ⌘K 命令面板 + ⌘/ 快捷键面板
 * - 首启 Onboarding 引导 + 全屏 Loading 覆盖
 * - 错误边界（外层 AppShell + 内层路由级）双层保护
 */
export function AppShell() {
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const isMobile = useIsMobile();
  useGlobalPaletteShortcut();
  useGlobalShortcuts();

  // mount 时同步主题与密度到 <html>，避免首屏闪烁；启动跨 tab 事件同步
  useEffect(() => {
    applyDisplayPrefsOnce();
    enableCrossTabSync();
  }, []);

  // 路由变化时自动关闭移动 Drawer
  useEffect(() => {
    if (isMobile) {
      const onPop = () => setMobileOpen(false);
      window.addEventListener('popstate', onPop);
      return () => window.removeEventListener('popstate', onPop);
    }
  }, [isMobile]);

  // ⌘K Quick Action「折叠/展开侧栏」：桌面端切 collapsed，移动端切 Drawer
  useEffect(() => {
    const onToggle = () => {
      if (isMobile) setMobileOpen((v) => !v);
      else setCollapsed((v) => !v);
    };
    window.addEventListener('agent-gateway:toggle-sidebar', onToggle);
    return () => window.removeEventListener('agent-gateway:toggle-sidebar', onToggle);
  }, [isMobile]);

  return (
    <ErrorBoundary scope="app">
      <AntLayout style={{ minHeight: '100vh' }}>
        <ResponsiveSidebar
          collapsed={collapsed}
          onCollapse={setCollapsed}
          mobileOpen={mobileOpen}
          onMobileClose={() => setMobileOpen(false)}
        />
        <AntLayout>
          <Header
            isMobile={isMobile}
            onToggleMobileMenu={() => setMobileOpen((v) => !v)}
          />
          <Content className="layout-content">
            <ErrorBoundary scope="route">
              <Outlet />
            </ErrorBoundary>
          </Content>
        </AntLayout>
        <CommandPalette />
        <ShortcutOverlay />
        <LoadingOverlay />
        <Onboarding />
      </AntLayout>
    </ErrorBoundary>
  );
}