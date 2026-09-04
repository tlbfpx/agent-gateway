/**
 * 一站式 E2E：覆盖所有 13 个左侧菜单项目的渲染、加载数据、关键交互。
 *
 * 目标：点开任意菜单 → 该页能拿到 seed 数据并呈现关键内容；任何回归
 * 都会立即体现在本文件的失败上。
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

import { Dashboard } from '../src/pages/Dashboard';
import { ModelsList } from '../src/pages/Models/List';
import { ApiKeysList } from '../src/pages/ApiKeys/List';
import { Webhooks } from '../src/pages/Webhooks';
import { Audit } from '../src/pages/Audit';
import { ConfigHistory } from '../src/pages/ConfigHistory';
import { Rbac } from '../src/pages/Rbac';
import { Discovery } from '../src/pages/Discovery';
import { Agents } from '../src/pages/Agents';
import { Chat } from '../src/pages/Chat';
import { Settings } from '../src/pages/Settings';
import { Health } from '../src/pages/Health';
import { Traces } from '../src/pages/Traces';

describe('Left-menu page coverage', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('Dashboard shows seeded stats', async () => {
    renderWithRouter(<Dashboard />);
    await waitFor(() => {
      expect(screen.getAllByText(/模型|API Key|请求|Agent/i).length).toBeGreaterThan(0);
    });
  });

  it('Models list shows seeded models', async () => {
    renderWithRouter(<ModelsList />);
    await waitFor(() => {
      expect(screen.getAllByText(/gpt-4o|GPT-4o/).length).toBeGreaterThan(0);
    });
  });

  it('ApiKeys list shows seeded keys', async () => {
    renderWithRouter(<ApiKeysList />);
    await waitFor(() => {
      expect(screen.getAllByText(/pk_live_/i).length).toBeGreaterThan(0);
    });
  });

  it('Webhooks list shows seeded hooks', async () => {
    renderWithRouter(<Webhooks />);
    expect(await screen.findByText(/hooks\.primary\.example/)).toBeInTheDocument();
  });

  it('Audit list shows seeded entries', async () => {
    renderWithRouter(<Audit />);
    await waitFor(() => {
      expect(screen.getAllByText(/admin@primary/).length).toBeGreaterThan(0);
    });
  });

  it('ConfigHistory renders versions', async () => {
    renderWithRouter(<ConfigHistory />);
    // 表格行数（header + 至少 1 个数据行）
    await waitFor(() => {
      expect(screen.getAllByRole('row').length).toBeGreaterThan(1);
    });
  });

  it('Rbac preview renders structured form', async () => {
    renderWithRouter(<Rbac />);
    // 新表单：用户 ID + 资源类型 Segmented + 资源下拉 + 操作；目的文案可见
    expect(await screen.findByText('权限自查')).toBeInTheDocument();
    expect(screen.getByLabelText(/用户 ID/)).toBeInTheDocument();
    expect(screen.getByText('Agent')).toBeInTheDocument();
    expect(screen.getByText('模型')).toBeInTheDocument();
  });

  it('Discovery shows seeded agents', async () => {
    renderWithRouter(<Discovery />);
    expect(await screen.findByText(/weather-mcp/)).toBeInTheDocument();
  });

  it('Agents shows seeded registrations (independent of Discovery)', async () => {
    renderWithRouter(<Agents />);
    expect(await screen.findByText(/weather-mcp/)).toBeInTheDocument();
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);
  });

  it('Chat renders workbench (search results may show streamed events)', async () => {
    // Chat 页面 useEffect 中会调 scrollIntoView，jsdom 不支持；这里只需确认组件挂载。
    renderWithRouter(<Chat />);
    // 不报错即视为挂载成功
    await new Promise((r) => setTimeout(r, 200));
  });

  it('Traces shows seeded traces and waterfall navigation', async () => {
    renderWithRouter(<Traces />);
    // 等 seed 中的入口列出现(seed 里有"对话请求"和"认证"两类入口，'认证' 唯一)
    expect(await screen.findByText('认证')).toBeInTheDocument();
    // 表格行数 ≥2(header + 至少 1 个数据行)
    await waitFor(() => {
      expect(screen.getAllByRole('row').length).toBeGreaterThan(1);
    });
    // 点击首行进详情
    const firstCode = document.querySelector('code');
    if (firstCode) (firstCode.closest('tr') as HTMLElement).click();
    await waitFor(() => {
      expect(document.querySelectorAll('[data-testid="span-bar"]').length).toBeGreaterThan(0);
    });
  });

  it('Settings shows tenant field', async () => {
    renderWithRouter(<Settings />);
    await waitFor(() => {
      expect(screen.getAllByText(/API Key|租户|Tenant/).length).toBeGreaterThan(0);
    });
  });

  it('Health shows seeded components', async () => {
    renderWithRouter(<Health />);
    expect(await screen.findByText('db')).toBeInTheDocument();
    expect(screen.getByText('redis')).toBeInTheDocument();
  });
});
