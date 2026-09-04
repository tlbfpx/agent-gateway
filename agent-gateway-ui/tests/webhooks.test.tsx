/**
 * webhooks.test.tsx — Webhooks 死信重新投递（运营评审 #12）
 *
 * 覆盖：
 *  1) 死信操作列点击"重新投递"弹 Popconfirm → 确认后调用 POST → 列表项移除
 *  2) 失败时 ErrorState 提示
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Webhooks } from '../src/pages/Webhooks';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  });

const failJson = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

const baseDeadLetters = [
  {
    url: 'https://hooks.primary.example/billing',
    event: 'cost.report.daily',
    attempts: 5,
    error: 'connection refused',
    lastTryAt: '2026-08-30T10:00:00Z',
  },
];

describe('Webhooks — 死信重新投递（运营#12）', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    mock = installMock();
    mock.on('GET', '/admin/webhooks', () => json([]));
    mock.on('GET', '/admin/webhooks/dead-letters', () => json(baseDeadLetters));
    mock.on('GET', '/admin/webhooks/history', () => json([]));
  });

  afterEach(() => mock.uninstall());

  it('点击"重新投递"弹 Popconfirm → 确认后调用 POST → 列表项移除', async () => {
    let redeliverCalls = 0;
    let deadLetters = baseDeadLetters.slice();
    mock.on('GET', '/admin/webhooks/dead-letters', () => json(deadLetters));
    mock.on('POST', '/admin/webhooks/dead-letters/redeliver', (ctx) => {
      redeliverCalls += 1;
      const body = (ctx.body ?? {}) as { url?: string; event?: string };
      deadLetters = deadLetters.filter(
        (d) => !(d.url === body.url && d.event === body.event),
      );
      return json({ ok: true, attempts: 6 });
    });

    renderWithRouter(<Webhooks />, { path: '/webhooks' });
    // 展开死信队列 Collapse（默认折叠）
    const dlqLabel = await screen.findByText('死信队列');
    fireEvent.click(dlqLabel);
    const table = await screen.findByRole('table');
    // 找到该行并点击重新投递
    const row = within(table).getByText('https://hooks.primary.example/billing').closest('tr');
    expect(row).not.toBeNull();
    const btn = within(row as HTMLElement).getByTestId('dlq-redeliver');
    fireEvent.click(btn);

    // Popconfirm 弹出"确定重新投递"
    const popconfirm = await screen.findByRole('tooltip');
    fireEvent.click(within(popconfirm).getByRole('button', { name: /重新投递/ }));
    await waitFor(() => {
      expect(redeliverCalls).toBe(1);
    });
    // 行应被移除（只查 dlq 这张 table，不查 Popconfirm portal）
    await waitFor(() => {
      expect(
        within(table).queryByText('https://hooks.primary.example/billing'),
      ).not.toBeInTheDocument();
    });
  });

  it('失败时 ErrorState 提示', async () => {
    mock.on('GET', '/admin/webhooks/dead-letters', () => json(baseDeadLetters));
    mock.on('POST', '/admin/webhooks/dead-letters/redeliver', () =>
      failJson(500, { message: 'dispatcher not configured' }),
    );

    renderWithRouter(<Webhooks />, { path: '/webhooks' });
    const dlqLabel = await screen.findByText('死信队列');
    fireEvent.click(dlqLabel);
    const table = await screen.findByRole('table');
    const row = within(table).getByText('https://hooks.primary.example/billing').closest('tr');
    const btn = within(row as HTMLElement).getByTestId('dlq-redeliver');
    fireEvent.click(btn);
    const popconfirm = await screen.findByRole('tooltip');
    fireEvent.click(within(popconfirm).getByRole('button', { name: /重新投递/ }));

    // ErrorState 应展示后端错误细节（来自 ApiError.message）
    await waitFor(() => {
      expect(screen.getByText(/dispatcher not configured/)).toBeInTheDocument();
    });
  });
});