import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AppShell } from '../src/layouts/AppShell';
import { Dashboard } from '../src/pages/Dashboard';

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: <AppShell />,
        children: [
          { path: 'dashboard', element: <Dashboard /> },
          { path: 'models', element: <div data-testid="models-stub">Models page</div> },
        ],
      },
    ],
    { initialEntries: [path] },
  );
  return render(
    <ConfigProvider locale={zhCN}>
      <RouterProvider router={router} />
    </ConfigProvider>,
  );
}

describe('AppShell', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('renders sidebar + header + outlet', () => {
    renderAt('/dashboard');
    expect(screen.getByText('Agent Gateway')).toBeInTheDocument();
    expect(screen.getAllByText(/仪表盘/).length).toBeGreaterThan(0);
  });

  it('sidebar shows the AG logo', () => {
    renderAt('/dashboard');
    expect(screen.getByText('AG')).toBeInTheDocument();
  });

  it('renders sidebar menu groups', () => {
    renderAt('/dashboard');
    // 总览 / 资源管理 / 应用 既出现在侧栏分组标题，也可能出现在面包屑
    expect(screen.getAllByText('总览').length).toBeGreaterThan(0);
    expect(screen.getAllByText('资源管理').length).toBeGreaterThan(0);
    expect(screen.getAllByText('应用').length).toBeGreaterThan(0);
  });

  it('renders dashboard page content via outlet', async () => {
    renderAt('/dashboard');
    expect(await screen.findByText(/系统运行态势/)).toBeInTheDocument();
  });
});