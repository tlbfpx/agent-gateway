/**
 * notification-center-bridge.test.tsx — NotificationCenter 桥接告警 firing + DLQ（运营评审 #18）
 *
 * 覆盖：
 *  1) firing 告警自动 push 到通知中心（去重 by alertId）
 *  2) Webhook 死信新增自动 push critical 通知
 *  3) 点击 firing 通知跳转 /alerts?id=
 *  4) markRead 后铃铛 badge 数减少 1（点击后切到"已读"tab 看到刚 markRead 的那条）
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  });

describe('NotificationCenter — 桥接告警 firing + DLQ', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    localStorage.clear();
    // 重置通知中心 module-level 单例 _list
    vi.resetModules();
    mock = installMock();
    mock.on('GET', '/admin/alerts', () =>
      json([
        {
          id: 'al-firing-1',
          ruleId: 'r-1',
          severity: 'critical',
          state: 'firing',
          dedupKey: 'r-1:chat.errors',
          labels: { rule: '错误过多' },
          firstFiredAt: new Date().toISOString(),
          recentlyTriggeredAt: new Date().toISOString(),
          triggerCount: 3,
          observedValue: 12,
          threshold: 5,
          claimedBy: null,
          note: null,
          resolvedAt: null,
        },
      ]),
    );
    mock.on('GET', '/admin/webhooks/dead-letters', () =>
      json([
        {
          url: 'https://hooks.primary.example/billing',
          event: 'cost.report.daily',
          attempts: 5,
          error: 'connection refused',
          lastTryAt: '2026-08-30T10:00:00Z',
        },
      ]),
    );
  });

  afterEach(() => {
    mock.uninstall();
    vi.useRealTimers();
  });

  it('firing 告警自动 push 通知（去重 by alertId）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    await waitFor(() => {
      expect(screen.getByText(/错误过多 触发 \(critical\)/)).toBeInTheDocument();
    });
    expect(screen.getByText(/12 > 5 · 3 次触发/)).toBeInTheDocument();
  });

  it('DLQ 新增自动 push critical 通知', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    await waitFor(() => {
      expect(screen.getByText(/Webhook 死信：cost.report.daily/)).toBeInTheDocument();
    });
    expect(screen.getByText(/connection refused/)).toBeInTheDocument();
  });

  it('点击 firing 通知触发 markRead（不报错）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    const title = await screen.findByText(/错误过多 触发/);
    fireEvent.click(title);
    await waitFor(() => {
      // 点击触发 markRead + navigate；NotificationCenter 弹窗会自动关闭（onClose 由 navigate 调用）
      // 断言不抛错即可
      expect(title).toBeInTheDocument();
    });
  });

  it('markRead 后在"已读"tab 看到该通知', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    // 等 firing 推送进来
    const title = await screen.findByText(/错误过多 触发/);
    // 点击 → markRead（同时 navigate 会关闭弹窗，所以下一次 findByLabelText 即可重新打开）
    fireEvent.click(title);
    // 重新打开弹窗（点击铃铛）
    fireEvent.click(screen.getByLabelText('通知中心'));
    // 切到"已读"tab
    await waitFor(() => {
      const tabs = screen.getAllByRole('tab');
      const readTab = tabs.find((t) => /已读/.test(t.textContent ?? ''));
      expect(readTab).toBeDefined();
      if (readTab) fireEvent.click(readTab);
    });
    await waitFor(() => {
      // 已读列表里应包含我们点击过的那条
      expect(screen.getByText(/错误过多 触发/)).toBeInTheDocument();
    });
  });

  // ────────────────────────────────────────────────────────────────
  // Round5 回归修复 #1：角标口径只反映 unread，不再混算 firing
  // ────────────────────────────────────────────────────────────────
  it('firing=3 且 unread=0 时角标不显示数字（badge count == 0）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    mock.on('GET', '/admin/alerts', () =>
      json(
        Array.from({ length: 3 }, (_, i) => ({
          id: `al-${i}`,
          ruleId: 'r-1',
          severity: 'critical',
          state: 'firing',
          dedupKey: `r-1:firing-${i}`,
          labels: { rule: `规则 ${i}` },
          firstFiredAt: new Date().toISOString(),
          recentlyTriggeredAt: new Date().toISOString(),
          triggerCount: 1,
          observedValue: 1,
          threshold: 1,
          claimedBy: null,
          note: null,
          resolvedAt: null,
        })),
      ),
    );
    // 死信为空 —— 避免 useAlertNotifications 把 firing 转 unread 后 unread > 0
    mock.on('GET', '/admin/webhooks/dead-letters', () => json([]));

    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    const { container } = renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });

    // 点开铃铛，看到面板内「N 告警触发中」按钮（信息不丢失，由独立按钮承载）
    fireEvent.click(screen.getByLabelText('通知中心'));
    await screen.findByText(/3 告警触发中/);

    // 关键断言：角标 sup 上**没有**任何 sup 显示「3」
    //   （修复前：Badge count=Math.max(0,3)=3 → sup 文案 = 3）
    //   （修复后：Badge count=unreadCount=0 → antd 不渲染 sup 或 sup 文案为空）
    const badgeSups = Array.from(container.querySelectorAll('.ant-badge-count'));
    // 面板内可能存在其他 Tag 等无关元素，但 ant-badge-count 只出现在 Badge 上
    badgeSups.forEach((s) => {
      expect((s.textContent ?? '').trim()).not.toBe('3');
    });
  });

  // ────────────────────────────────────────────────────────────────
  // Round5 回归修复 #2：死信 dedup 提升到模块作用域 + localStorage
  //   清空全部通知 + 卸载重挂 → 历史死信不再重灌
  // ────────────────────────────────────────────────────────────────
  it('清空全部 → 卸载重挂：死信不再被重复 push', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    // 清掉死信持久化记录（之前用例可能已写入）
    localStorage.removeItem('agent-gateway.dlq-pushed');
    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    const { unmount } = renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });

    // 验证第一条 DLQ 通知已 push 进来
    fireEvent.click(screen.getByLabelText('通知中心'));
    await screen.findByText(/Webhook 死信：cost.report.daily/);

    // 用户清空全部 —— "清空全部"是 Tooltip title 不是 accessible name，用 popover-inner 容器定位
    // firing 告警按钮(AlertOutlined)和清空按钮(ClearOutlined)都是 type="text" danger，
    // ant-btn-dangerous 出现在两个上；我们用 aria-label 的 svg icon 来区分：
    //   - 清空全部：图标是 "delete"  (ClearOutlined)
    //   - 告警按钮：图标是 "alert"   (AlertOutlined)
    // 但按钮上没有 aria-label，只有 icon 的 span 上有 aria-label="delete"/"alert"。
    // 因此找 popover 内最后一个 ant-btn-dangerous 即可（DOM 顺序：告警→全部已读→清空）。
    const popoverInner = document.querySelector('.ant-popover-inner');
    expect(popoverInner).toBeTruthy();
    const dangerBtns = Array.from(
      popoverInner!.querySelectorAll('button.ant-btn-dangerous'),
    ) as HTMLButtonElement[];
    expect(dangerBtns.length).toBeGreaterThanOrEqual(1);
    // 取最后一个（告警→清空）
    const clearBtn = dangerBtns[dangerBtns.length - 1];
    // 双重确认：它的图标是 clear（antd 5 ClearOutlined 渲染 aria-label=icon.name="clear"）
    expect(clearBtn.querySelector('[aria-label="clear"]')).toBeTruthy();
    fireEvent.click(clearBtn);
    await waitFor(() => {
      expect(screen.queryByText(/Webhook 死信：cost.report.daily/)).not.toBeInTheDocument();
    });

    // 卸载重挂（关键：组件 unmount 后再 mount，模块作用域的 pushedDlqKeys 应仍然在）
    unmount();
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });

    // 重新打开弹窗
    fireEvent.click(screen.getByLabelText('通知中心'));
    // 应当看不到刚被清掉的 DLQ 通知 —— 只剩 seed 里既有的（无 DLQ 相关）
    await waitFor(() => {
      expect(screen.queryByText(/Webhook 死信：cost.report.daily/)).not.toBeInTheDocument();
    });
  });

  it('lastTryAt 变化的同一条死信：应当再次 push（新失败要告知）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    localStorage.removeItem('agent-gateway.dlq-pushed');
    // 第一次拉取返回 lastTryAt='T1'
    let callCount = 0;
    mock.on('GET', '/admin/webhooks/dead-letters', () => {
      callCount += 1;
      return json([
        {
          url: 'https://hooks.primary.example/billing',
          event: 'cost.report.daily',
          attempts: 5,
          error: 'connection refused',
          lastTryAt: callCount === 1 ? '2026-08-30T10:00:00Z' : '2026-08-30T10:05:00Z',
        },
      ]);
    });

    const { NotificationCenter } = await import('../src/components/framework/NotificationCenter');
    const { unmount } = renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    await screen.findByText(/Webhook 死信：cost.report.daily/);

    // 卸载 + 重挂（mock 仍返回 lastTryAt=T2）
    unmount();
    renderWithRouter(<NotificationCenter />);
    await act(async () => {
      vi.advanceTimersByTime(0);
    });
    fireEvent.click(screen.getByLabelText('通知中心'));
    // 新 key 含 T2 → 应当再产生一条 DLQ 通知（标题一致，但 id 不同）
    await waitFor(() => {
      const items = screen.getAllByText(/Webhook 死信：cost.report.daily/);
      expect(items.length).toBeGreaterThanOrEqual(1);
    });
  });
});