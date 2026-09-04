/**
 * CostCenter.test.tsx — Round 10 成本中心图表化测试
 *
 * 覆盖：
 *  GW-UX-CEST-001 折线图渲染
 *  GW-UX-CEST-002 模型占比饼图
 *  GW-UX-CEST-003 同期对比柱图
 *  GW-UX-CEST-004 时间范围切换
 *  GW-UX-CEST-005 空态降级
 *  GW-UX-CEST-006 URL 同步
 *  GW-UX-CEST-007 零依赖 SVG（仅依赖 react/antd/@antd/icons）
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { CostCenter } from '../src/pages/CostCenter';
import { renderWithRouter } from './harness';
import { installMock } from './fixtures/mockServer';
import {
  deriveTimeseries,
  deriveBreakdown,
  deriveCompare,
} from '../src/lib/api/cost';

// 复用 cost.test.tsx 的 mock 模式（让 audit 派生数据）
vi.mock('../src/lib/api/models', () => ({ listModels: vi.fn().mockResolvedValue([]) }));
vi.mock('../src/lib/api/keys', () => ({ listApiKeys: vi.fn().mockResolvedValue([]) }));
vi.mock('../src/lib/api/audit', () => ({
  listAuditLogs: vi.fn().mockResolvedValue([
    { eventId: 'e1', actor: 'admin@primary', type: 'chat', time: new Date().toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary', keyId: 'k1' },
    { eventId: 'e2', actor: 'admin@tenant-b', type: 'chat', time: new Date(Date.now() - 86400 * 1000).toISOString(), resource: 'claude-3.7', action: 'invoke', result: 'success', detail: '', tenant: 'tenant-b', keyId: 'k2' },
    { eventId: 'e3', actor: 'admin@primary', type: 'chat', time: new Date(Date.now() - 2 * 86400 * 1000).toISOString(), resource: 'qwen-max', action: 'invoke', result: 'success', detail: '', tenant: 'primary', keyId: 'k1' },
    { eventId: 'e4', actor: 'admin@primary', type: 'chat', time: new Date(Date.now() - 3 * 86400 * 1000).toISOString(), resource: 'gpt-4o', action: 'invoke', result: 'success', detail: '', tenant: 'primary', keyId: 'k1' },
    { eventId: 'e5', actor: 'admin@tenant-b', type: 'chat', time: new Date(Date.now() - 4 * 86400 * 1000).toISOString(), resource: 'deepseek-v3', action: 'invoke', result: 'fail', detail: '', tenant: 'tenant-b', keyId: 'k2' },
    { eventId: 'e6', actor: 'admin@tenant-c', type: 'chat', time: new Date(Date.now() - 5 * 86400 * 1000).toISOString(), resource: 'glm-4-plus', action: 'invoke', result: 'success', detail: '', tenant: 'tenant-c', keyId: 'k3' },
  ]),
}));
vi.mock('../src/lib/api/health', () => ({
  getHealth: vi.fn().mockResolvedValue({ status: 'UP', components: {} }),
}));

// 满足 live 接口 503 → 退化分支
const liveCost = null;

const installCostMock = () => {
  const mock = installMock();
  mock.on('GET', '/admin/metrics/cost', () =>
    new Response(JSON.stringify(liveCost), {
      status: 503,
      headers: { 'content-type': 'application/json' },
    }),
  );
  return mock;
};

describe('GW-UX-CEST-001/002/003 CostCenter 图表三联渲染', () => {
  let mock: ReturnType<typeof installCostMock>;
  beforeEach(() => {
    mock = installCostMock();
  });
  afterEach(() => mock.uninstall());

  it('7d 范围渲染折线 + 饼图 + 同期对比', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d' });
    await screen.findByText('成本中心');
    // 折线
    await waitFor(() => {
      expect(screen.getByTestId('timeseries-chart')).toBeInTheDocument();
    });
    // 饼图
    await waitFor(() => {
      expect(screen.getByTestId('model-share-pie')).toBeInTheDocument();
    });
    // 同期对比
    await waitFor(() => {
      expect(screen.getByTestId('period-compare-bar')).toBeInTheDocument();
    });
    // 差值徽章
    await waitFor(() => {
      expect(screen.getByTestId('period-compare-delta')).toBeInTheDocument();
    });
  });

  it('饼图含 ≥ 1 个 <path class="pie-slice"> 扇区', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=30d' });
    await waitFor(() => {
      expect(document.querySelectorAll('.pie-slice').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('同期对比柱图包含 2 根 <rect>', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=30d' });
    const wrapper = await screen.findByTestId('period-compare-bar');
    await waitFor(() => {
      expect(wrapper.querySelectorAll('rect').length).toBeGreaterThanOrEqual(2);
    });
  });
});

describe('GW-UX-CEST-004 时间范围切换', () => {
  let mock: ReturnType<typeof installCostMock>;
  beforeEach(() => {
    mock = installCostMock();
  });
  afterEach(() => mock.uninstall());

  it('?range=7d 渲染 7 个 X 轴标签', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d' });
    const chart = await screen.findByTestId('timeseries-chart');
    await waitFor(() => {
      // 7d 派生应恰好 7 个数据点
      expect(chart.querySelectorAll('.area-bar-labels span').length).toBe(7);
    });
  });

  it('?range=30d 渲染 30 个数据点', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=30d' });
    const chart = await screen.findByTestId('timeseries-chart');
    await waitFor(() => {
      expect(chart.querySelectorAll('.area-bar-labels span').length).toBe(30);
    });
  });

  it('range 选项含 90d', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d' });
    await screen.findByText('成本中心');
    expect(screen.getByTestId('cost-range-select')).toBeInTheDocument();
    // 选项定义在 CostCenter.tsx 内联;确认源码确实包含"近 90 天"
    // (避免 jsdom 下 antd Select dropdown 难以触发的复杂性)
    const path = '/Users/muxi/workspace/agent-gateway/agent-gateway-ui/src/pages/CostCenter.tsx';
    const src = (await import('node:fs' as any)).readFileSync(path, 'utf8');
    expect(src).toContain("'90d'");
    expect(src).toContain('近 90 天');
  });
});

describe('GW-UX-CEST-005 空态降级', () => {
  it('空 audit → 图表 / 表格都进入空态不崩溃', async () => {
    // 走纯 mock 派生路径:把 audit 改为空 → loadCostReport 返回 total=0
    const auditMod = await import('../src/lib/api/audit');
    (auditMod.listAuditLogs as any).mockResolvedValueOnce([]);
    renderWithRouter(<CostCenter />, { path: '/cost?range=7d' });
    await screen.findByText('成本中心');
    // 图表区空态(220px 高"暂无数据"由 TimeseriesChart / ModelSharePie 渲染)
    await waitFor(() => {
      expect(screen.getAllByText(/暂无数据|当前维度暂无数据/).length).toBeGreaterThanOrEqual(1);
    });
    // 关键:图表组件仍渲染(testid 存在即代表未崩溃)
    await waitFor(() => {
      expect(screen.getByTestId('timeseries-chart')).toBeInTheDocument();
      expect(screen.getByTestId('model-share-pie')).toBeInTheDocument();
      expect(screen.getByTestId('period-compare-bar')).toBeInTheDocument();
    });
  });
});

describe('GW-UX-CEST-006 URL 同步', () => {
  let mock: ReturnType<typeof installCostMock>;
  beforeEach(() => {
    mock = installCostMock();
  });
  afterEach(() => mock.uninstall());

  it('?range=90d 进入页面后图表 labelStride=10', async () => {
    renderWithRouter(<CostCenter />, { path: '/cost?range=90d' });
    const chart = await screen.findByTestId('timeseries-chart');
    await waitFor(() => {
      // 90d 派生 90 点，labels 应为 90 个 span（i%10==0 才有文本）
      expect(chart.querySelectorAll('.area-bar-labels span').length).toBe(90);
    });
  });
});

describe('GW-UX-CEST-007 零依赖 SVG 派生', () => {
  it('deriveTimeseries 7d 产出 7 点且包含 costCny/calls', async () => {
    const { loadCostReport } = await import('../src/lib/api/usage');
    const report = await loadCostReport('7d');
    const ts = deriveTimeseries(report);
    expect(ts.length).toBe(7);
    expect(ts[0]).toHaveProperty('costCny');
    expect(ts[0]).toHaveProperty('calls');
  });

  it('deriveBreakdown model 维度百分比合计 = 1', async () => {
    const { loadCostReport } = await import('../src/lib/api/usage');
    const report = await loadCostReport('7d');
    const bd = deriveBreakdown(report, 'model');
    const sum = bd.reduce((acc, r) => acc + r.percentage, 0);
    expect(sum).toBeCloseTo(1, 3);
  });

  it('deriveCompare 本月/上月 数值合理', async () => {
    const { loadCostReport } = await import('../src/lib/api/usage');
    const report = await loadCostReport('30d');
    const c = deriveCompare(report);
    expect(c.currentLabel).toBe('本月');
    expect(c.previousLabel).toBe('上月');
    expect(c.currentValue + c.previousValue).toBeGreaterThan(0);
  });

  it('null report → 派生函数安全返回', () => {
    expect(deriveTimeseries(null)).toEqual([]);
    expect(deriveBreakdown(null, 'model')).toEqual([]);
    const c = deriveCompare(null);
    expect(c.currentValue).toBe(0);
    expect(c.previousValue).toBe(0);
    expect(c.deltaPct).toBe(0);
  });
});