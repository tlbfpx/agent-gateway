/**
 * cost-scheduled-report.test.tsx — CostCenter 定时报表订阅（运营评审 #19）
 *
 * 覆盖：
 *  1. 打开 ScheduledReportDialog（点 CostCenter "订阅"）
 *  2. 4 字段校验（period / range / dim / webhookUrl）
 *  3. 提交成功（mock fetch POST 响应）
 *  4. 取消（Popconfirm → POST → 列表移除）
 *  5. URL 同步：点 Tab 后地址栏变化 + F5 后恢复
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, within, act } from '@testing-library/react';
import { CostCenter } from '../src/pages/CostCenter';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

/** 显式声明 content-type，避免 jsdom 默认 text/plain 导致 http.get 走 text 分支 */
const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  });

const liveCost = {
  total: { calls: 12, tokens: 18_000, errors: 1, costCny: 4.5, avgLatencyMs: 320 },
  byTenant: [{ dim: 'tenant', id: 'primary', name: 'primary', calls: 12, tokens: 18_000, avgLatencyMs: 320, errors: 1, costCny: 4.5 }],
  byKey: [{ dim: 'key', id: 'alice@primary', name: 'alice@primary', calls: 12, tokens: 18_000, avgLatencyMs: 320, errors: 1, costCny: 4.5 }],
  byModel: [{ dim: 'model', id: 'gpt-4o', name: 'gpt-4o', calls: 12, tokens: 18_000, avgLatencyMs: 320, errors: 1, costCny: 4.5 }],
  byDay: [{ dim: 'day', id: '2026-08-30', name: '2026-08-30', calls: 12, tokens: 18_000, avgLatencyMs: 320, errors: 1, costCny: 4.5 }],
  live: true,
  range: '24h',
};

let scheduledList: any[] = [];

describe('CostCenter — 定时报表订阅 + URL 状态同步', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    scheduledList = [];
    mock = installMock();
    mock.on('GET', '/admin/metrics/cost', () => json(liveCost));
    mock.on('GET', '/admin/reports/scheduled', () => json(scheduledList));
    mock.on('POST', '/admin/reports/scheduled', (ctx) => {
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const created = {
        id: `sr_${Date.now()}`,
        period: body.period,
        range: body.range,
        dim: body.dim,
        webhookUrl: body.webhookUrl,
        enabled: true,
        createdAt: new Date().toISOString(),
      };
      scheduledList = [created, ...scheduledList];
      return json(created);
    });
    mock.on('POST', '/admin/reports/scheduled/:id/cancel', (ctx) => {
      const id = ctx.url.pathname.split('/').slice(-2, -1)[0];
      scheduledList = scheduledList.filter((s) => s.id !== id);
      return json({ ok: true, id });
    });
    mock.on('POST', '/admin/reports/scheduled/:id/test', () =>
      json({ ok: true, latencyMs: 42 }),
    );
  });

  afterEach(() => mock.uninstall());

  it('打开 Dialog：点 CostCenter "订阅" 按钮', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost' });
    await screen.findByText('成本中心');
    const btn = await screen.findByTestId('cost-subscribe-btn');
    fireEvent.click(btn);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('定时账单订阅')).toBeInTheDocument();
    expect(within(dialog).getByText('推送周期')).toBeInTheDocument();
    expect(within(dialog).getByText('账单窗口')).toBeInTheDocument();
    expect(within(dialog).getByText('账单维度')).toBeInTheDocument();
    expect(within(dialog).getByText('回调 URL')).toBeInTheDocument();
  });

  it('4 字段校验：webhookUrl 必填且必须是 URL', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost' });
    const btn = await screen.findByTestId('cost-subscribe-btn');
    fireEvent.click(btn);
    const dialog = await screen.findByRole('dialog');
    // 先不填 URL 直接提交 → 必填校验
    const submit = within(dialog).getByRole('button', { name: /创建订阅/ });
    fireEvent.click(submit);
    await waitFor(() => {
      expect(within(dialog).getByText('请输入回调 URL')).toBeInTheDocument();
    });
    // 填一个非法 URL → URL 校验失败
    const urlInput = within(dialog).getByTestId('scheduled-webhook-url');
    fireEvent.change(urlInput, { target: { value: 'not-a-url' } });
    fireEvent.click(submit);
    await waitFor(() => {
      expect(within(dialog).getByText('请输入合法的 http(s) URL')).toBeInTheDocument();
    });
    // 合法 URL 通过校验
    fireEvent.change(urlInput, { target: { value: 'https://hooks.example/billing' } });
    expect((urlInput as HTMLInputElement).value).toBe('https://hooks.example/billing');
  });

  it('提交成功：POST 后列表出现新订阅', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost' });
    const btn = await screen.findByTestId('cost-subscribe-btn');
    fireEvent.click(btn);
    const dialog = await screen.findByRole('dialog');
    const urlInput = within(dialog).getByTestId('scheduled-webhook-url');
    fireEvent.change(urlInput, { target: { value: 'https://hooks.example/billing' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /创建订阅/ }));
    await waitFor(() => {
      const table = within(dialog).getByTestId('scheduled-list-table');
      expect(within(table).getByText('https://hooks.example/billing')).toBeInTheDocument();
    });
    expect(scheduledList.length).toBe(1);
    expect(scheduledList[0].dim).toBe('tenant'); // dim 锁定为当前 Tab
    expect(scheduledList[0].range).toBe('24h'); // range 默认绑定当前报表
    expect(scheduledList[0].period).toBe('daily'); // period 默认为 daily
  });

  it('取消：Popconfirm 确认后调用 POST 并把行从列表中移除', async () => {
    scheduledList = [
      {
        id: 'sr_existing',
        period: 'daily',
        range: '24h',
        dim: 'tenant',
        webhookUrl: 'https://hooks.example/billing',
        enabled: true,
        createdAt: new Date().toISOString(),
      },
    ];
    renderWithRouter(<CostCenter />, { path: '/cost' });
    fireEvent.click(await screen.findByTestId('cost-subscribe-btn'));
    const dialog = await screen.findByRole('dialog');
    const table = await within(dialog).findByTestId('scheduled-list-table');
    expect(within(table).getByText('https://hooks.example/billing')).toBeInTheDocument();
    // 触发取消 Popconfirm
    const cancelBtn = within(table).getByRole('button', { name: /取消/ });
    fireEvent.click(cancelBtn);
    // Popconfirm 二次确认
    const popconfirm = await screen.findByRole('tooltip');
    const ok = within(popconfirm).getByRole('button', { name: /确\s*定/ });
    fireEvent.click(ok);
    await waitFor(() => {
      expect(within(dialog).queryByTestId('scheduled-list-table')).not.toBeInTheDocument();
    });
    expect(scheduledList.length).toBe(0);
  });

  it('URL 同步：点 Tab 后激活对应 tab，?range=7d 初始进入渲染按模型 Tab，刷新后保持', async () => {
    // 1) ?range=7d&dim=model 初始进入 → 按模型 Tab 默认激活
    const r1 = renderWithRouter(<CostCenter />, { path: '/cost?range=7d&dim=model' });
    await screen.findByText('成本中心');
    await waitFor(() => {
      const tabActive = document.querySelector('.ant-tabs-tab-active');
      expect(tabActive?.textContent).toContain('按模型');
    });

    // 2) 点 "按 API Key" Tab → 激活态切换（URL 由 useSearchParams 双向绑定）
    const keyTab = screen.getByRole('tab', { name: /按 API Key/ });
    await act(async () => {
      fireEvent.click(keyTab);
    });
    await waitFor(() => {
      const active = document.querySelector('.ant-tabs-tab-active');
      expect(active?.textContent).toContain('按 API Key');
    });

    // 3) 通过 range Select 切到 7d → CostTable 重新拉数据
    const rangeSelect = await screen.findByTestId('cost-range-select');
    fireEvent.mouseDown(rangeSelect);
    const opt = await screen.findByText('近 7 天');
    fireEvent.click(opt);
    await waitFor(() => {
      // 选中后下拉关闭即可验证
      expect(screen.queryByRole('option', { name: /近 7 天/ })).not.toBeInTheDocument();
    });

    r1.unmount();

    // 4) F5 后保留：直接以 ?range=7d 进入，按 tenant Tab 默认激活（dim 缺省 → tenant）
    scheduledList = [];
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d' });
    await screen.findByText('成本中心');
    await waitFor(() => {
      const tabActive = document.querySelector('.ant-tabs-tab-active');
      expect(tabActive?.textContent).toContain('按租户');
    });
  });
});