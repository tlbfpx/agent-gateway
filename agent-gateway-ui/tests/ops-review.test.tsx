/**
 * ops-review.test.tsx — 运营评审三项改动的回归测试
 *
 * 任务1：Audit 服务端查询（type/result/from/keyword/offset + 分页）
 * 任务2：AlertCenter 30s 自动轮询（页面可见 + 失败不打断数据 + 页面标注）
 * 任务3：ApiKeys 7 天内到期运营提醒横幅 + 快捷过滤
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { screen, waitFor, fireEvent, act } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';
import { Audit, rangeToFrom } from '../src/pages/Audit';
import { AlertCenter } from '../src/pages/AlertCenter';
import { ApiKeysList } from '../src/pages/ApiKeys/List';
import { listAuditLogs } from '../src/lib/api/audit';

/** 包装 mock fetch，记录审计查询 URL */
function recordAuditFetch(): { calls: string[] } {
  const calls: string[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = ((input: any, init?: any) => {
    const url = typeof input === 'string' ? input : input?.url ?? String(input);
    if (url.includes('/admin/audit/logs')) calls.push(url);
    return real(input, init);
  }) as typeof fetch;
  return { calls };
}

function bulkAudit(n: number) {
  const now = Date.now();
  return Array.from({ length: n }, (_, i) => ({
    id: 'ae_bulk_' + String(i).padStart(3, '0'),
    ts: new Date(now - i * 60_000).toISOString(),
    actor: 'bulk@primary',
    type: i % 2 === 0 ? 'chat' : 'admin',
    action: i % 2 === 0 ? 'chat.invoke' : 'admin.update',
    resource: 'gpt-4o',
    result: i % 3 === 0 ? 'deny' : 'success',
    reason: '',
  }));
}

describe('任务1：Audit 服务端查询与分页', () => {
  let mock: ReturnType<typeof installMock>;
  let rec: ReturnType<typeof recordAuditFetch>;

  beforeEach(() => {
    mock = installMock();
    (mock.store.audit as unknown as unknown[]).push(...bulkAudit(60));
    rec = recordAuditFetch();
  });
  afterEach(() => {
    mock.uninstall();
    vi.useRealTimers();
  });

  it('rangeToFrom: 窗口映射为 ISO Instant，全部返回 undefined', () => {
    const from24 = rangeToFrom('24h')!;
    expect(Number.isNaN(new Date(from24).getTime())).toBe(false);
    expect(new Date(from24).getTime()).toBeGreaterThan(Date.now() - 25 * 3600_000);
    expect(new Date(from24).getTime()).toBeLessThan(Date.now() - 23 * 3600_000);
    expect(rangeToFrom('7d')).toBeTruthy();
    expect(rangeToFrom('30d')).toBeTruthy();
    expect(rangeToFrom('all')).toBeUndefined();
  });

  it('listAuditLogs: type/result/from/keyword/offset 全部作为查询参数下发', async () => {
    await listAuditLogs({ tenant: 'primary', type: 'chat', result: 'DENIED', from: '2026-01-01T00:00:00Z', keyword: 'hello', limit: 5, offset: 10 });
    const url = rec.calls.at(-1)!;
    expect(url).toContain('tenant=primary');
    expect(url).toContain('type=chat');
    expect(url).toContain('result=DENIED');
    expect(url).toContain('from=2026-01-01T00%3A00%3A00Z');
    expect(url).toContain('keyword=hello');
    expect(url).toContain('limit=5');
    expect(url).toContain('offset=10');
  });

  it('默认渲染即发起服务端查询（含 from/limit/offset），无客户端过滤', async () => {
    renderWithRouter(<Audit />);
    await waitFor(() => {
      expect(screen.getAllByText(/bulk@primary/).length).toBeGreaterThan(0);
    });
    const url = rec.calls.at(-1)!;
    expect(url).toContain('limit=50');
    expect(url).toContain('offset=0');
    expect(url).toContain('from=');
    // 默认 24h 窗口内 61 条 seed，但服务端只回 50 条
    await waitFor(() => {
      expect(screen.getByText(/本页 50 条/)).toBeInTheDocument();
    });
  });

  it('分页：下一页 offset=50，上一页回到 0；无更多时下一页禁用', async () => {
    renderWithRouter(<Audit />);
    await waitFor(() => expect(screen.getByText(/本页 50 条/)).toBeInTheDocument());
    const next = screen.getByTestId('audit-next-page') as HTMLButtonElement;
    const prev = screen.getByTestId('audit-prev-page') as HTMLButtonElement;
    expect(prev.disabled).toBe(true);
    expect(next.disabled).toBe(false);
    fireEvent.click(next);
    await waitFor(() => expect(rec.calls.at(-1)!).toContain('offset=50'));
    expect(screen.getByTestId('audit-prev-page')).toBeEnabled();
    // 64 条（4 seed + 60 bulk）：第二页只有 14 条 → 无下一页
    await waitFor(() => expect(screen.getByText(/本页 14 条/)).toBeInTheDocument());
    expect(screen.getByTestId('audit-next-page')).toBeDisabled();
    fireEvent.click(screen.getByTestId('audit-prev-page'));
    await waitFor(() => expect(rec.calls.at(-1)!).toContain('offset=0'));
  });

  it('keyword 走服务端，且筛选变化时 offset 重置', async () => {
    renderWithRouter(<Audit />);
    await waitFor(() => expect(screen.getByText(/本页 50 条/)).toBeInTheDocument());
    // 翻到第二页
    fireEvent.click(screen.getByTestId('audit-next-page'));
    await waitFor(() => expect(rec.calls.at(-1)!).toContain('offset=50'));
    // 输入关键字 → offset 重置为 0 且 keyword 下发
    const input = screen.getByPlaceholderText(/搜索 actor\/resource\/action\/eventId/) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'service-bot' } });
    await waitFor(() => {
      const url = rec.calls.at(-1)!;
      expect(url).toContain('keyword=service-bot');
      expect(url).toContain('offset=0');
    });
  });

  it('类型筛选走服务端参数 type=', async () => {
    const { container } = renderWithRouter(<Audit />);
    await waitFor(() => expect(screen.getByText(/本页 50 条/)).toBeInTheDocument());
    // 第 2 个 Select 是“类型筛选”
    const selects = container.querySelectorAll('.ant-select');
    fireEvent.mouseDown(selects[1].querySelector('.ant-select-selector')!);
    const opt = await waitFor(() => {
      const o = screen.getAllByText('admin').find((el) => el.className.includes('ant-select-item-option-content'));
      expect(o).toBeTruthy();
      return o!;
    });
    fireEvent.click(opt);
    await waitFor(() => expect(rec.calls.at(-1)!).toContain('type=admin'));
  });

  it('range=全部 时不发 from', async () => {
    const { container } = renderWithRouter(<Audit />);
    await waitFor(() => expect(screen.getByText(/本页 50 条/)).toBeInTheDocument());
    const selects = container.querySelectorAll('.ant-select');
    fireEvent.mouseDown(selects[3].querySelector('.ant-select-selector')!);
    const opt = await waitFor(() => {
      const o = screen.getAllByText('全部').find((el) => el.className.includes('ant-select-item-option-content'));
      expect(o).toBeTruthy();
      return o!;
    });
    fireEvent.click(opt);
    await waitFor(() => {
      expect(rec.calls.at(-1)!).not.toContain('from=');
    });
  });
});

describe('任务2：AlertCenter 30s 自动轮询', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    mock = installMock();
    mock.on('GET', '/admin/alerts/rules', () => new Response(JSON.stringify([]), { status: 200, headers: { 'content-type': 'application/json' } }));
    mock.on('GET', '/admin/alerts', () =>
      new Response(
        JSON.stringify([
          { id: 'al-1', ruleId: 'r-1', severity: 'critical', state: 'firing', dedupKey: 'r-1', firstFiredAt: '2026-08-18T00:00:00Z', recentlyTriggeredAt: '2026-08-18T01:00:00Z', triggerCount: 3, claimedBy: null, note: null },
        ]),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
  });
  afterEach(() => {
    mock.uninstall();
    vi.useRealTimers();
  });

  it('页面标注“每 30 秒自动刷新”，且每 30s 轮询刷新数据', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    let hits = 0;
    const real = globalThis.fetch;
    globalThis.fetch = ((input: any, init?: any) => {
      const url = typeof input === 'string' ? input : input?.url ?? String(input);
      if (url.includes('/admin/alerts?') || url.replace(/\?.*/, '').endsWith('/admin/alerts')) hits += 1;
      return real(input, init);
    }) as typeof fetch;

    renderWithRouter(<AlertCenter />);
    expect(await screen.findByText('每 30 秒自动刷新')).toBeInTheDocument();
    await screen.findByText('规则 · 0');
    const before = hits;
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    expect(hits).toBeGreaterThan(before);
  });

  it('轮询失败不打断现有数据（静默）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    renderWithRouter(<AlertCenter />);
    await screen.findByText('触发中 · 1');
    // 下一次轮询返回 500
    mock.nextReply('GET', '/admin/alerts', { message: 'boom' }, 500);
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    // 数据仍在，且不出现错误态
    expect(screen.getByText('触发中 · 1')).toBeInTheDocument();
  });
});

describe('任务3：ApiKeys 即将到期运营提醒', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('存在 7 天内到期/已过期 Key 时显示横幅，并可快捷过滤', async () => {
    const now = Date.now();
    mock.store.apiKeys.push(
      { id: 'pk_live_expiring', owner: 'ops', tenant: 'primary', enabled: true, createdAt: new Date(now - 80 * 86400_000).toISOString(), expiresAt: new Date(now + 3 * 86400_000).toISOString() } as never,
      { id: 'pk_live_expired', owner: 'ops', tenant: 'primary', enabled: true, createdAt: new Date(now - 80 * 86400_000).toISOString(), expiresAt: new Date(now - 1 * 86400_000).toISOString() } as never,
    );
    renderWithRouter(<ApiKeysList />);
    expect(await screen.findByText(/2 个 Key 将在 7 天内到期或已过期/)).toBeInTheDocument();
    expect(screen.getByText(/已过期 1 个/)).toBeInTheDocument();
    expect(screen.getByText(/即将到期 1 个/)).toBeInTheDocument();
    // 快捷过滤：已过期
    fireEvent.click(screen.getByTestId('filter-expired'));
    await waitFor(() => {
      expect(screen.getByText(/已签发 Key · 1/)).toBeInTheDocument();
    });
    // 快捷过滤：即将到期
    fireEvent.click(screen.getByTestId('filter-expiring'));
    await waitFor(() => {
      expect(screen.getByText(/已签发 Key · 1/)).toBeInTheDocument();
    });
    // 清除过滤
    fireEvent.click(screen.getByText('清除过滤'));
    await waitFor(() => {
      expect(screen.getByText(/已签发 Key · 5/)).toBeInTheDocument();
    });
  });

  it('无即将到期 Key 时不显示横幅', async () => {
    renderWithRouter(<ApiKeysList />);
    await screen.findByText(/已签发 Key · 3/);
    expect(screen.queryByText(/将在 7 天内到期/)).toBeNull();
  });
});
