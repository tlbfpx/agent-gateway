/**
 * tests/Dashboard.test.tsx — Dashboard 健康判定（Round 10 fix）
 *
 * 覆盖：
 *  A. 全 UP → 'up' + "全部正常" 文案
 *  B. 部分 WARN（DB latencyMs=300）→ 'slow' + "部分降级" 文案
 *  C. 全 DOWN → 'down' + "存在故障" 文案
 *  D. 混合 WARN + DOWN → 'down'（down 优先级高，slow 被覆盖）
 *  E. cache 命中率 0.4 → 'slow'（派生 warning）
 *  F. cache 命中率 0.1 → 'down'（派生 error）
 *
 * 历史背景：Dashboard.tsx:104-117 的 hasWarn 永 false + 没有 warning 分支 → 'slow' 死代码。
 * 本测试确保修复后 slow 状态可达、warning 分支被识别、down 优先级正确。
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';
import { Dashboard } from '../src/pages/Dashboard';

/** 构造 readiness 报告（与后端 HealthController 形状一致） */
function ready(checks: Record<string, string | { status: string; details?: Record<string, unknown> }>) {
  return { status: 'READY', checks };
}

/** 找到 Dashboard "组件健康" 卡内的 pill 元素，返回 class 列表 */
function getHealthPillClass(): string {
  const title = screen.getByText('组件健康');
  const head = title.closest('.content-card-head') as HTMLElement;
  const pill = head.querySelector('.health-pill') as HTMLElement;
  return pill.className;
}

describe('Dashboard — 健康判定 (Round 10 fix)', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('A · 全 UP → 整体 up + "全部正常"', async () => {
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'UP', details: { latencyMs: 12 } },
        redis: { status: 'UP' },
        nacos: { status: 'UP' },
      })), { headers: { 'content-type': 'application/json' } }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--up/);
    });
    await waitFor(() => {
      expect(screen.getByText('全部正常')).toBeInTheDocument();
    });
  });

  it('B · DB latencyMs=300 (派生 warning) → slow + "部分降级"', async () => {
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'UP', details: { latencyMs: 300 } }, // 100~500 区间 → warning
        redis: { status: 'UP' },
        nacos: { status: 'UP' },
      })), { headers: { 'content-type': 'application/json' } }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    // 等 warning 分支被识别 → setHealthStatus('slow')
    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--slow/);
    });
    await waitFor(() => {
      expect(screen.getByText('部分降级')).toBeInTheDocument();
    });
  });

  it('C · 全 DOWN → down + "存在故障"', async () => {
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'DOWN' },
        redis: { status: 'DOWN' },
      })), { headers: { 'content-type': 'application/json' } }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--down/);
    });
    await waitFor(() => {
      expect(screen.getByText('存在故障')).toBeInTheDocument();
    });
  });

  it('D · 混合 WARN + DOWN → down 覆盖 slow', async () => {
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'UP', details: { latencyMs: 250 } }, // warning
        redis: { status: 'DOWN' },                          // error
      })), { headers: { 'content-type': 'application/json' } }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    // down 优先级最高，即使有 warning 也应是 down
    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--down/);
      expect(cls).not.toMatch(/health-pill--slow/);
    });
    await waitFor(() => {
      expect(screen.getByText('存在故障')).toBeInTheDocument();
      expect(screen.queryByText('部分降级')).toBeNull();
    });
  });

  it('E · cache 命中率 0.4 (派生 warning) → slow', async () => {
    // 让 readiness 全 UP（details 无 latencyMs）
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'UP' },
        redis: { status: 'UP' },
      })), { headers: { 'content-type': 'application/json' } }),
    );
    // 调整 actuator 计数器：hits=4, misses=6 → rate=0.4 → 派生 warning
    mock.store.promptCache.hits = 4;
    mock.store.promptCache.misses = 6;

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--slow/);
    });
    await waitFor(() => {
      expect(screen.getByText('部分降级')).toBeInTheDocument();
    });
  });

  it('F · cache 命中率 0.1 (派生 error) → down', async () => {
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        db: { status: 'UP' },
        redis: { status: 'UP' },
      })), { headers: { 'content-type': 'application/json' } }),
    );
    // hits=1, misses=9 → rate=0.1 < 0.3 → 派生 error
    mock.store.promptCache.hits = 1;
    mock.store.promptCache.misses = 9;

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--down/);
    });
    await waitFor(() => {
      expect(screen.getByText('存在故障')).toBeInTheDocument();
    });
  });

  it('G · 后端原生 WARN 状态字符串 → warning 分支直接命中', async () => {
    // 后端 HealthController 后续扩展时可回 'WARN' / 'DEGRADED'
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify(ready({
        provider: { status: 'WARN' },
        db: { status: 'UP' },
      })), { headers: { 'content-type': 'application/json' } }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      expect(cls).toMatch(/health-pill--slow/);
    });
    // 至少 1 行 WARN 文案
    await waitFor(() => {
      expect(screen.getByText(/WARN/)).toBeInTheDocument();
    });
  });

  it('H · ready 接口整体 503 → unknown', async () => {
    // 让 ready 返回 503 + 空 checks（极端场景）
    mock.on('GET', '/v1/ready', () =>
      new Response(JSON.stringify({ status: 'NOT_READY', checks: {} }), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      }),
    );

    renderWithRouter(<Dashboard />);
    await screen.findByText('组件健康');

    await waitFor(() => {
      const cls = getHealthPillClass();
      // 空 checks → 'unknown'
      expect(cls).toMatch(/health-pill--unknown/);
    });
  });
});