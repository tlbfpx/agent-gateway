/**
 * ops-review-drilldown.test.tsx — 运营评审第二批三项改动回归测试
 *
 * 任务1：CostCenter → Budgets（URL 参数 tenant 联动过滤）→ AlertCenter（相关告警入口）
 * 任务2：Dashboard 活动流下钻（认证/限流类 → Audit?keyword=，Agent 调用类 → Traces）
 *        健康检查异常时显示"去处理"链接到 /health
 * 任务3：灰度对比报表一句话结论（错误率/延迟分位自动生成，样本 < 30 提示延长观察）
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';
import { CostCenter } from '../src/pages/CostCenter';
import { Budgets } from '../src/pages/Budgets';
import { AlertCenter } from '../src/pages/AlertCenter';
import { Dashboard } from '../src/pages/Dashboard';
import {
  buildGrayscaleConclusion,
  GrayscaleConclusion,
  GRAY_MIN_SAMPLES,
} from '../src/components/models/GrayscaleDialog';
import type { GrayscaleMember } from '../src/lib/api/models';

function mkMember(p: Partial<GrayscaleMember>): GrayscaleMember {
  return {
    modelId: 'm',
    displayName: 'M',
    provider: 'openai',
    weight: 50,
    enabled: true,
    requests: 100,
    errors: 0,
    errorRate: 0,
    p50LatencyMs: 100,
    p95LatencyMs: 200,
    tokensIn: 0,
    tokensOut: 0,
    costCny: 0,
    ...p,
  };
}

// ================= 任务1：成本 → 预算 → 告警下钻 =================

describe('任务1：CostCenter → Budgets → AlertCenter 下钻', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('成本切片行提供"查看预算"（/budgets?tenant=）与"相关告警"（/alerts?q=）入口', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost' });
    // seed 审计聚合出 tenant 维度行（primary）
    const budgetLinks = await waitFor(() => {
      const ls = screen.getAllByTestId('cost-drill-budget');
      expect(ls.length).toBeGreaterThan(0);
      return ls as HTMLAnchorElement[];
    });
    expect(budgetLinks[0].getAttribute('href')).toBe('/budgets?tenant=primary');
    const alertLinks = screen.getAllByTestId('cost-drill-alert') as HTMLAnchorElement[];
    expect(alertLinks[0].getAttribute('href')).toBe('/alerts?q=primary');
  });

  it('Budgets 读取 ?tenant= 自动过滤最近用量记账，并可清除', async () => {
    const rec = (id: string, tenant: string) => ({
      recordId: id,
      tenant: { value: tenant },
      user: { value: 'ops@' + tenant },
      model: { value: 'gpt-4o' },
      agentName: 'bot-' + tenant,
      timestamp: '2026-08-18T10:00:00Z',
      tokensIn: 10,
      tokensOut: 20,
      cost: 0.5,
      unitPriceIn: 0.01,
      unitPriceOut: 0.02,
    });
    mock.on('GET', '/admin/billing/budgets', () => new Response('null', { status: 200 }));
    mock.on('GET', '/admin/billing/costs', () =>
      new Response(JSON.stringify([rec('r1', 'primary'), rec('r2', 'tenant-b'), rec('r3', 'tenant-b')]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    renderWithRouter(<Budgets />, { path: '/budgets?tenant=tenant-b' });
    // 过滤横幅出现，且只展示 tenant-b 的 2 条记账
    expect(await screen.findByTestId('budgets-drilldown-filter')).toBeInTheDocument();
    expect(screen.getAllByText(/tenant-b/).length).toBeGreaterThan(0);
    await waitFor(() => {
      expect(screen.getByText(/2 \/ 3 条/)).toBeInTheDocument();
    });
    expect(screen.getAllByText('ops@tenant-b').length).toBe(2);
    expect(screen.queryByText('ops@primary')).toBeNull();
    // 清除过滤后横幅消失，全部记账恢复可见
    fireEvent.click(screen.getByText('清除过滤'));
    await waitFor(() => {
      expect(screen.queryByTestId('budgets-drilldown-filter')).toBeNull();
    });
    expect(await screen.findByText('ops@primary')).toBeInTheDocument();
    expect(screen.getAllByText('ops@tenant-b').length).toBe(2);
  });

  it('Budgets 下钻横幅提供"查看相关告警"入口', async () => {
    mock.on('GET', '/admin/billing/budgets', () => new Response('null', { status: 200 }));
    mock.on('GET', '/admin/billing/costs', () => new Response('[]', { status: 200 }));
    renderWithRouter(<Budgets />, { path: '/budgets?tenant=primary' });
    const link = (await screen.findByText('查看相关告警')) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/alerts?q=primary');
  });

  it('AlertCenter 读取 ?q= 过滤告警流', async () => {
    mock.on('GET', '/admin/alerts/rules', () => new Response('[]', { status: 200 }));
    mock.on('GET', '/admin/alerts', () =>
      new Response(
        JSON.stringify([
          { id: 'al-1', ruleId: 'cost-primary-over', severity: 'critical', state: 'firing', dedupKey: 'k1', firstFiredAt: '2026-08-18T00:00:00Z', recentlyTriggeredAt: '2026-08-18T01:00:00Z', triggerCount: 2, claimedBy: null, note: null },
          { id: 'al-2', ruleId: 'latency-tenant-b', severity: 'warning', state: 'resolved', dedupKey: 'k2', firstFiredAt: '2026-08-18T00:00:00Z', recentlyTriggeredAt: '2026-08-18T01:00:00Z', triggerCount: 1, claimedBy: null, note: null },
        ]),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
    renderWithRouter(<AlertCenter />, { path: '/alerts?q=primary' });
    expect(await screen.findByTestId('alerts-drilldown-filter')).toBeInTheDocument();
    expect(screen.getByText(/1 \/ 2 条/)).toBeInTheDocument();
    expect(screen.getByText('cost-primary-over')).toBeInTheDocument();
    expect(screen.queryByText('latency-tenant-b')).toBeNull();
    // 清除过滤后横幅消失，firing 流恢复展示
    fireEvent.click(screen.getByText('清除过滤'));
    await waitFor(() => {
      expect(screen.queryByTestId('alerts-drilldown-filter')).toBeNull();
    });
    expect(screen.getByText('cost-primary-over')).toBeInTheDocument();
  });
});

// ================= 任务2：Dashboard 下钻 =================

describe('任务2：Dashboard 活动流与健康下钻', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('认证/限流类活动 → /audit?keyword=；Agent 调用类活动 → /traces', async () => {
    renderWithRouter(<Dashboard />, { path: '/dashboard' });
    const links = await waitFor(() => {
      const ls = screen.getAllByTestId('activity-drill-link') as HTMLAnchorElement[];
      expect(ls.length).toBeGreaterThan(0);
      return ls;
    });
    const hrefs = links.map((l) => l.getAttribute('href')!);
    // seed：api-key.create（密钥类）→ Audit + keyword=资源；chat.invoke（调用类）→ Traces
    expect(hrefs.some((h) => h.startsWith('/audit?keyword='))).toBe(true);
    expect(hrefs).toContain('/traces');
    const auditHref = hrefs.find((h) => h.startsWith('/audit?keyword='))!;
    expect(decodeURIComponent(auditHref)).toMatch(/pk_live_|models\//);
  });

  it('健康检查存在异常组件时显示"去处理"链接到 /health；全部正常时不显示', async () => {
    // 覆写 /ready：redis DOWN
    mock.on('GET', '/ready', () =>
      new Response(
        JSON.stringify({
          status: 'NOT_READY',
          checks: {
            db: { status: 'UP' },
            redis: { status: 'DOWN' },
          },
        }),
        { status: 503, headers: { 'content-type': 'application/json' } },
      ),
    );
    renderWithRouter(<Dashboard />, { path: '/dashboard' });
    const fix = await waitFor(() => {
      const l = screen.getByTestId('health-fix-link') as HTMLAnchorElement;
      expect(l).toBeInTheDocument();
      return l;
    });
    expect(fix.getAttribute('href')).toBe('/health');
  });

  it('全部组件 UP 时不显示"去处理"', async () => {
    renderWithRouter(<Dashboard />, { path: '/dashboard' });
    await waitFor(() => {
      expect(screen.getByText('全部正常')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('health-fix-link')).toBeNull();
  });
});

// ================= 任务3：灰度报表结论 =================

describe('任务3：灰度对比报表结论建议', () => {
  it('样本充足时生成含错误率倍数与 P95 延迟对比的一句话结论', () => {
    const a = mkMember({ modelId: 'model-a', displayName: 'A', requests: 1000, errorRate: 0.015, p95LatencyMs: 900 });
    const b = mkMember({ modelId: 'model-b', displayName: 'B', requests: 500, errorRate: 0.002, p95LatencyMs: 600 });
    const text = buildGrayscaleConclusion([a, b]);
    expect(text).toContain('成员 B 错误率 0.2% vs A 1.5%');
    expect(text).toContain('7.5x');
    expect(text).toContain('P95 延迟更低');
    expect(text).toContain('建议逐步提高权重或全量切换');
  });

  it('请求样本 < 30 时提示样本不足，建议延长观察', () => {
    const a = mkMember({ modelId: 'model-a', displayName: 'A', requests: 500, errorRate: 0.01 });
    const b = mkMember({ modelId: 'model-b', displayName: 'B', requests: GRAY_MIN_SAMPLES - 1, errorRate: 0.001 });
    const text = buildGrayscaleConclusion([a, b]);
    expect(text).toContain('样本不足');
    expect(text).toContain('延长观察');
  });

  it('成员不足 2 个时提示无法对比', () => {
    expect(buildGrayscaleConclusion([mkMember({})])).toContain('不足 2 个');
  });

  it('GrayscaleConclusion 组件渲染 warning（样本不足）与 success（建议切换）', () => {
    const { unmount } = renderWithRouter(
      <GrayscaleConclusion
        members={[
          mkMember({ modelId: 'a', requests: 10, errorRate: 0.01 }),
          mkMember({ modelId: 'b', requests: 20, errorRate: 0.02 }),
        ]}
      />,
    );
    expect(screen.getByTestId('grayscale-conclusion')).toBeInTheDocument();
    expect(screen.getByText('灰度结论建议')).toBeInTheDocument();
    expect(screen.getByText(/样本不足/)).toBeInTheDocument();
    unmount();
    renderWithRouter(
      <GrayscaleConclusion
        members={[
          mkMember({ modelId: 'a', requests: 100, errorRate: 0.02 }),
          mkMember({ modelId: 'b', requests: 100, errorRate: 0.001 }),
        ]}
      />,
    );
    expect(screen.getByText(/错误率/)).toBeInTheDocument();
    expect(screen.getByText(/提高权重或全量切换/)).toBeInTheDocument();
  });
});
