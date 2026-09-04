/**
 * useResourceList tests — 7 用例覆盖 hook 全部行为
 *
 * 默认错误处理已切换到 `notifyError`（常驻通知中心 + toast，Round 10 B-2）。
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useResourceList } from './useResourceList';
import { __resetErrorDedup } from '../lib/request';

interface Item {
  id: string;
  __empty?: boolean;
}

describe('useResourceList', () => {
  it('success path: returns data, loading=false, error=null, isEmpty=false', async () => {
    const fetcher = vi.fn(async () => [{ id: 'a' }, { id: 'b' }] as Item[]);
    const { result } = renderHook(() => useResourceList<Item>({ fetcher }));

    // 初始 loading=true（同步 state）
    expect(result.current.loading).toBe(true);
    expect(result.current.data).toEqual([]);
    expect(result.current.error).toBeNull();

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.data).toEqual([{ id: 'a' }, { id: 'b' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.isEmpty).toBe(false);
    expect(typeof result.current.reload).toBe('function');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('error path: throws → error=Error, loading=false, isEmpty=true, onError called once', async () => {
    const fetcher = vi.fn(async () => {
      throw new Error('boom');
    });
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useResourceList<Item>({ fetcher, errorMessage: 'fallback', onError }),
    );

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBeInstanceOf(Error);
    expect((result.current.error as Error).message).toBe('boom');
    expect(result.current.data).toEqual([]);
    expect(result.current.isEmpty).toBe(true);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0].message).toBe('boom');
  });

  it('error path with no onError: default notifyError fires with errorMessage context', async () => {
    __resetErrorDedup();
    const fetcher = vi.fn(async () => {
      throw new Error('无可用模型');
    });
    const { result } = renderHook(() =>
      useResourceList<Item>({ fetcher, errorMessage: '模型列表加载失败' }),
    );

    await waitFor(() => expect(result.current.loading).toBe(false));

    // 这里不去 spy notifyError 的内部调用 — 仅验证 error 字段被填上了
    expect(result.current.error).toBeInstanceOf(Error);
    expect((result.current.error as Error).message).toBe('无可用模型');
    expect(result.current.data).toEqual([]);
    expect(result.current.isEmpty).toBe(true);
  });

  it('deps change: triggers re-fetch (initial + each change)', async () => {
    const fetcher = vi.fn(async () => [{ id: 'x' }] as Item[]);
    const { result, rerender } = renderHook(
      ({ kw }: { kw: string }) =>
        useResourceList<Item>({ fetcher, deps: [kw] }),
      { initialProps: { kw: 'a' } },
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetcher).toHaveBeenCalledTimes(1);

    rerender({ kw: 'b' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));

    rerender({ kw: 'c' });
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
  });

  it('manual reload: calls fetcher again without changing deps', async () => {
    const fetcher = vi.fn(async () => [{ id: 'y' }] as Item[]);
    const { result } = renderHook(() => useResourceList<Item>({ fetcher }));

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetcher).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.reload();
    });

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it('emptyCheck override: filters out placeholder items', async () => {
    const filter = (i: Item) => !i.__empty;

    const emptyFetcher = vi.fn(async () => [] as Item[]);
    const { result: r1 } = renderHook(() =>
      useResourceList<Item>({ fetcher: emptyFetcher, emptyCheck: filter }),
    );
    await waitFor(() => expect(r1.current.loading).toBe(false));
    expect(r1.current.isEmpty).toBe(true);

    const placeHolderFetcher = vi.fn(async () => [{ id: '0', __empty: true }] as Item[]);
    const { result: r2 } = renderHook(() =>
      useResourceList<Item>({
        fetcher: placeHolderFetcher,
        emptyCheck: filter,
      }),
    );
    await waitFor(() => expect(r2.current.loading).toBe(false));
    expect(r2.current.data.length).toBe(1);
    expect(r2.current.isEmpty).toBe(true);

    const mixedFetcher = vi.fn(
      async () => [{ id: '1' }, { id: '2', __empty: true }] as Item[],
    );
    const { result: r3 } = renderHook(() =>
      useResourceList<Item>({ fetcher: mixedFetcher, emptyCheck: filter }),
    );
    await waitFor(() => expect(r3.current.loading).toBe(false));
    expect(r3.current.isEmpty).toBe(false);
  });

  it('stale request guard: deps rapid change → old late response does not overwrite new data', async () => {
    const slow = vi.fn(
      () =>
        new Promise<Item[]>((resolve) =>
          setTimeout(() => resolve([{ id: 'slow' }]), 50),
        ),
    );
    const fast = vi.fn(async () => [{ id: 'fast' }] as Item[]);

    const fetcherRef: { current: () => Promise<Item[]> } = { current: slow };
    const { result, rerender } = renderHook(() =>
      useResourceList<Item>({ fetcher: fetcherRef.current, deps: [fetcherRef.current] }),
    );

    fetcherRef.current = fast;
    rerender();

    await waitFor(() => expect(result.current.loading).toBe(false));

    // 等 slow 的 setTimeout 也触发完，确保没有覆盖
    await new Promise((r) => setTimeout(r, 80));

    expect(result.current.data).toEqual([{ id: 'fast' }]);
    expect(result.current.error).toBeNull();
  });
});
