import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { RouterProvider } from 'react-router-dom';
import { router } from './routes';
// ⚠️ tokens.css 必须在 global.css 之前导入 — 它定义了 :root CSS 变量
// （--brand-deep / --bg-canvas / --text-* 等）。原先写在 global.css 内的
// @import './tokens.css' 因不在文件首条规则之后被浏览器静默丢弃，导致整个
// 站点的 CSS 变量未生效，sidebar/header/body 全部失去品牌色基底。
import './styles/tokens.css';
import './styles/global.css';

/**
 * Ant Design 主题 token — 与 styles/tokens.css 的 CSS 变量一一对应。
 * 深色模式的色板来自 [data-theme="dark"] 块，保证 antd 组件
 * （Modal / Table / Drawer / Popover 等 JS token 渲染物）与
 * 页面自定义样式的视觉一致，避免"半黑半白"。
 */
const sharedTokens = {
  colorPrimary: '#1677ff',
  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#ff4d4f',
  colorInfo: '#1677ff',
  borderRadius: 6,
  fontFamily: '"Noto Sans SC", "PingFang SC", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, sans-serif',
  fontFamilyCode: '"JetBrains Mono", "SF Mono", Consolas, monospace',
};

const lightConfig = {
  token: {
    ...sharedTokens,
    colorBgLayout: '#F5F7FB',
    colorBgContainer: '#FFFFFF',
    colorBgElevated: '#FFFFFF',
    colorBorder: '#E5E8EF',
    colorBorderSecondary: '#EEF0F5',
    colorText: '#1A2237',
    colorTextSecondary: '#4E5870',
    colorTextTertiary: '#8A93A8',
    colorTextQuaternary: '#B4BBCB',
  },
  algorithm: theme.defaultAlgorithm,
};

const darkConfig = {
  token: {
    ...sharedTokens,
    colorBgLayout: '#0E1422',
    colorBgContainer: '#161E33',
    colorBgElevated: '#1E2740',
    colorBorder: '#2A3354',
    colorBorderSecondary: '#1E2740',
    colorText: '#E8ECF7',
    colorTextSecondary: '#B4BBCB',
    colorTextTertiary: '#8A93A8',
    colorTextQuaternary: '#5A6584',
  },
  algorithm: theme.darkAlgorithm,
};

function readTheme(): 'light' | 'dark' {
  return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
}

/**
 * ThemedApp — 监听 <html data-theme> 变化并切换 antd 主题。
 * 用 MutationObserver 而非回调事件：无论谁改属性（DisplaySwitcher /
 * Quick Action / system matchMedia），都能收到通知。
 */
function ThemedApp() {
  const [mode, setMode] = useState<'light' | 'dark'>(readTheme);

  useEffect(() => {
    const observer = new MutationObserver(() => setMode(readTheme()));
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    return () => observer.disconnect();
  }, []);

  return (
    <ConfigProvider theme={mode === 'dark' ? darkConfig : lightConfig} locale={zhCN}>
      <RouterProvider router={router} />
    </ConfigProvider>
  );
}

const container = document.getElementById('root');
if (!container) throw new Error('#root element not found');

createRoot(container).render(
  <StrictMode>
    <ThemedApp />
  </StrictMode>,
);
