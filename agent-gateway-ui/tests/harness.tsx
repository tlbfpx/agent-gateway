/**
 * tests/harness.tsx — 测试公共渲染器
 *
 * 提供：
 *  - renderPage(node)            直接在 ConfigProvider 中渲染
 *  - renderWithRouter(node, path?)  使用 MemoryRouter 包裹，使被测组件能
 *                                    正常使用 useLocation / useNavigate 等
 *                                    路由 hooks（无需注册的 routes）
 */

import { render } from '@testing-library/react';
import type { RenderOptions, RenderResult } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';

const Providers = ({ children }: { children: ReactNode }) => (
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: { colorPrimary: '#1677ff', borderRadius: 6 },
      algorithm: theme.defaultAlgorithm,
    }}
  >
    {children}
  </ConfigProvider>
);

export interface PageRenderOpts extends Omit<RenderOptions, 'wrapper'> {
  path?: string;
  /** 兼容旧调用签名，忽略 */
  shell?: boolean;
}

export function renderPage(ui: ReactNode, opts: PageRenderOpts = {}): RenderResult {
  return render(ui, { wrapper: Providers, ...opts });
}

export function renderWithRouter(
  ui: ReactNode,
  opts: PageRenderOpts & { routes?: never } = {},
): RenderResult {
  const path = opts.path ?? '/';
  return render(
    <Providers>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </Providers>,
    opts,
  );
}
