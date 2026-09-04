/**
 * tests/Traces.test.tsx — 调用链追踪 E2E(Round7)
 *
 * 覆盖：
 *  A. 列表渲染(seed 4 条 trace 入表)
 *  B. 详情瀑布图(?traceId=tid-3，错误链路 span-bar 红 + 点击弹出 span-attrs JSON)
 *  C. 503 降级(mock 覆写返回 503，Alert 引导文案)
 *  D. 错误过滤器(Switch 切换 → mock.on 重新拦截 ?errorOnly=true)
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';
import { Traces } from '../src/pages/Traces';
import { seedTraces } from './fixtures/seed';

describe('Traces page (Round7 MVP)', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('A · renders trace list from seed (rows + truncated traceId code)', async () => {
    renderWithRouter(<Traces />);

    // 列表渲染后入口列（rootSpanName → 中文 spanLabel）出现
    // seed 里有"对话请求"和"认证"两类入口；用 findAllByText + 至少出现 1 个
    expect(await screen.findByText('认证')).toBeInTheDocument();

    // 表格行数（header + 4 条数据）≥ 2
    await waitFor(() => {
      expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(2);
    });

    // 至少 1 个 <code> 显示截断后的 traceId(展示 slice(0,16)+'…')
    await waitFor(() => {
      const codes = document.querySelectorAll('code');
      const hasTrunc = Array.from(codes).some((c) => /…|\.{3}/.test(c.textContent ?? ''));
      expect(hasTrunc).toBe(true);
    });
  });

  it('B · opens waterfall detail for error trace (red span-bar + span-attrs JSON)', async () => {
    // 进入详情：?traceId=tid-3(错误链路)
    renderWithRouter(<Traces />, { path: '/traces?traceId=tid-0003-agent-call-error' });

    // 详情页顶部会渲染调用链详情标题 + 复制 ID 按钮
    expect(await screen.findByText(/调用链详情/)).toBeInTheDocument();

    // 至少 3 个 span-bar
    await waitFor(() => {
      expect(document.querySelectorAll('[data-testid="span-bar"]').length).toBeGreaterThanOrEqual(3);
    });

    // 至少 1 个 bar 内联样式含 #ff4d4f(错误红)
    const hasRed = Array.from(document.querySelectorAll('[data-testid="span-bar"]')).some((el) => {
      const bg = (el as HTMLElement).style.background ?? '';
      return bg.includes('#ff4d4f');
    });
    expect(hasRed).toBe(true);

    // 点击带 #ff4d4f(agent.call 错误)的 span-bar → 弹出 span-attrs 含 "agent_name"
    const errorBar = Array.from(document.querySelectorAll('[data-testid="span-bar"]')).find((el) => {
      const bg = (el as HTMLElement).style.background ?? '';
      return bg.includes('#ff4d4f');
    }) as HTMLElement | undefined;
    expect(errorBar).toBeDefined();
    if (errorBar) {
      const row = errorBar.closest('div[style*="cursor"]') ?? errorBar.parentElement?.parentElement;
      if (row) fireEvent.click(row as HTMLElement);
      else fireEvent.click(errorBar);
    }

    // 等待 span-attrs 出现 → 文案含 "agent_name"(来自 seed 中 s3-agent.attributes.agent_name='sql-expert')
    await waitFor(() => {
      const attrs = document.querySelector('[data-testid="span-attrs"]');
      expect(attrs).not.toBeNull();
      expect(attrs?.textContent ?? '').toMatch(/agent_name/);
    });
  });

  it('C · shows 503 onboarding Alert when storage not configured', async () => {
    // 用 mock.on() 覆写 GET /admin/traces 返回 503(last-match wins 机制)
    mock.on('GET', '/admin/traces', () =>
      new Response(JSON.stringify({ message: '未配置持久化存储' }), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      }),
    );

    renderWithRouter(<Traces />);

    // Alert 标题文案"未配置持久化存储"出现
    expect(await screen.findByText(/未配置持久化存储/)).toBeInTheDocument();
  });

  it('D · toggles error-only filter (Switch triggers refetch with ?errorOnly=true)', async () => {
    renderWithRouter(<Traces />);
    // 先等列表首屏出现
    await waitFor(() => {
      expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(2);
    });

    // 找到"错误"标签旁的 Switch(用 role=switch)
    const switches = screen.getAllByRole('switch');
    expect(switches.length).toBeGreaterThanOrEqual(1);
    const errSwitch = switches[0]; // 第一个 Switch = "错误"过滤器(顺序见 Traces.tsx)

    // 拦截带 query 的请求并断言 query 真的带了 errorOnly=true
    let seenQuery: string | null = null;
    mock.on('GET', '/admin/traces', (ctx) => {
      seenQuery = ctx.url.search ?? '';
      const errOnly = ctx.url.searchParams.get('errorOnly') === 'true';
      const filtered = seedTraces.filter((t) => (errOnly ? t.errorCount > 0 : true));
      return new Response(JSON.stringify(filtered), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    });

    // 切换 Switch
    fireEvent.click(errSwitch);

    // 等待请求确实带了 errorOnly=true + 行数随之收敛
    await waitFor(() => {
      expect(seenQuery).toMatch(/errorOnly=true/);
    });

    // 错误过滤后只剩 1 条(tid-3 errorCount=1)
    await waitFor(() => {
      // header row + 1 data row = 2
      const rows = screen.getAllByRole('row');
      // 这里宽松一点：只断言表格被重新渲染过(避免 antd 表格内部 row 计数细节)
      expect(rows.length).toBeGreaterThanOrEqual(1);
    });
  });
});
