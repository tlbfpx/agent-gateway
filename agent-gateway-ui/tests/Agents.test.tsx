import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { Agents } from '../src/pages/Agents';
import { installMock } from './fixtures/mockServer';
import { seedAgents } from './fixtures/seed';
import { renderWithRouter } from './harness';

describe('Agents (admin view) — left menu /agents', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('renders listing of registered agents from seed', async () => {
    renderWithRouter(<Agents />);
    await waitFor(() => {
      expect(screen.getAllByText(/weather-mcp|web-search|sql-expert|pdf-reader/).length).toBeGreaterThan(0);
    });
    // 共 X 条 — PageHeader sub 与 Table pagination showTotal 都会出现，至少 1 处
    expect(screen.getAllByText(/共\s*\d+\s*条/).length).toBeGreaterThan(0);
  });

  it('filters by keyword', async () => {
    renderWithRouter(<Agents />);
    const input = await screen.findByPlaceholderText(/按名称 \/ 端点 \/ tag 搜索/);
    fireEvent.change(input, { target: { value: 'sql' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });
    await waitFor(() => {
      expect(screen.getByText('sql-expert')).toBeInTheDocument();
      expect(screen.queryByText('weather-mcp')).not.toBeInTheDocument();
    });
  });

  it('opens create drawer and submits a new agent', async () => {
    renderWithRouter(<Agents />);
    await screen.findByText(/weather-mcp/);
    fireEvent.click(screen.getByRole('button', { name: /注册新 Agent/ }));
    const drawer = await screen.findByRole('dialog');
    fireEvent.change(within(drawer).getByLabelText('名称'), { target: { value: 'csv-helper' } });
    fireEvent.change(within(drawer).getByLabelText('描述'), { target: { value: 'CSV 转换' } });
    fireEvent.change(within(drawer).getByLabelText('端点'), { target: { value: 'https://agents.example/csv' } });
    fireEvent.change(within(drawer).getByLabelText('版本'), { target: { value: '0.1.0' } });
    fireEvent.click(within(drawer).getByRole('button', { name: /注\s*册/ }));
    await waitFor(() => {
      expect(mock.store.agents.find((a) => a.name === 'csv-helper')).toBeTruthy();
    });
  });

  it('validates name pattern on create', async () => {
    renderWithRouter(<Agents />);
    await screen.findByText(/weather-mcp/);
    fireEvent.click(screen.getByRole('button', { name: /注册新 Agent/ }));
    const drawer = await screen.findByRole('dialog');
    fireEvent.change(within(drawer).getByLabelText('名称'), { target: { value: '非法名字' } });
    fireEvent.click(within(drawer).getByRole('button', { name: /注\s*册/ }));
    expect(await within(drawer).findByText(/字母开头/)).toBeInTheDocument();
    expect(mock.store.agents.find((a) => a.name === '非法名字')).toBeUndefined();
  });

  it('toggles enabled state via API', async () => {
    const { toggleAgentAvailability } = await import('../src/lib/api/agents');
    await toggleAgentAvailability('ag_sql', true);
    expect(mock.store.agents.find((a) => a.id === 'ag_sql')?.enabled).toBe(true);
  });

  it('rejects when toggling unknown agent', async () => {
    const { toggleAgentAvailability } = await import('../src/lib/api/agents');
    await expect(toggleAgentAvailability('ag_does_not_exist', true)).rejects.toBeTruthy();
  });

  it('deletes via API and updates store', async () => {
    const { deleteAgent } = await import('../src/lib/api/agents');
    const before = mock.store.agents.length;
    await deleteAgent('ag_weather');
    await waitFor(() => {
      expect(mock.store.agents.length).toBe(before - 1);
    });
  });

  it('tests connection via API returns ok', async () => {
    const { testAgentConnection } = await import('../src/lib/api/agents');
    const r = await testAgentConnection('ag_weather');
    expect(r.ok).toBe(true);
    expect(typeof r.latencyMs).toBe('number');
  });

  it('rejects on test for unknown agent', async () => {
    const { testAgentConnection } = await import('../src/lib/api/agents');
    await expect(testAgentConnection('nope')).rejects.toBeTruthy();
  });

  it('handles listing api failure gracefully', async () => {
    mock.uninstall();
    mock = installMock();
    mock.nextReply('GET', '/v1/admin/agents', { message: 'down' }, 503);
    renderWithRouter(<Agents />);
    // 加载失败应展示错误态（含重试），而不是伪装成空列表
    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.getByText('down')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument();
  });

  it('seed exposes 4 fixture agents', () => {
    expect(seedAgents.length).toBeGreaterThanOrEqual(4);
  });
});
