/**
 * auto-open-create.test.tsx — ?action=create 自动打开创建抽屉
 * 验证 ⌘K Quick Actions 的跳转承诺真正兑现
 */
import { describe, it, expect, vi } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useAutoOpenCreate } from '../src/hooks/useAutoOpenCreate';

function Probe() {
  useAutoOpenCreate(() => {
    (window as any).__probeCalled = ((window as any).__probeCalled ?? 0) + 1;
  });
  return <div>probe</div>;
}

describe('useAutoOpenCreate', () => {
  it('action=create 触发回调', async () => {
    (window as any).__probeCalled = 0;
    render(
      <MemoryRouter initialEntries={['/models?action=create']}>
        <Probe />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect((window as any).__probeCalled).toBe(1);
    });
  });

  it('无 action 参数不触发', async () => {
    (window as any).__probeCalled = 0;
    render(
      <MemoryRouter initialEntries={['/models']}>
        <Probe />
      </MemoryRouter>,
    );
    // 等一拍确保 useEffect 已跑
    await new Promise((r) => setTimeout(r, 50));
    expect((window as any).__probeCalled ?? 0).toBe(0);
  });

  it('action=other 不触发', async () => {
    (window as any).__probeCalled = 0;
    render(
      <MemoryRouter initialEntries={['/models?action=edit']}>
        <Probe />
      </MemoryRouter>,
    );
    await new Promise((r) => setTimeout(r, 50));
    expect((window as any).__probeCalled ?? 0).toBe(0);
  });
});
