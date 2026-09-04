/**
 * event-bus.test.ts — 跨页面数据同步
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { emit, on, useEvent, useEmit, listEvents } from '../src/hooks/useEventBus';

describe('EventBus', () => {
  it('emit 触发 listener', () => {
    const fn = vi.fn();
    on('models:changed', fn);
    emit('models:changed', undefined);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('emit 携带 payload', () => {
    const fn = vi.fn();
    on('tenant:switched', fn);
    emit('tenant:switched', { tenant: 'tenant-b' });
    expect(fn).toHaveBeenCalledWith({ tenant: 'tenant-b' });
  });

  it('返回的 unsubscribe 函数有效', () => {
    const fn = vi.fn();
    const off = on('models:changed', fn);
    off();
    emit('models:changed', undefined);
    expect(fn).not.toHaveBeenCalled();
  });

  it('多个 listener 都收到', () => {
    const a = vi.fn();
    const b = vi.fn();
    on('alerts:changed', a);
    on('alerts:changed', b);
    emit('alerts:changed', undefined);
    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(1);
  });

  it('listener 抛出不影响其他', () => {
    const a = vi.fn(() => {
      throw new Error('boom');
    });
    const b = vi.fn();
    on('apikeys:changed', a);
    on('apikeys:changed', b);
    // 不应该抛出到测试
    expect(() => emit('apikeys:changed', undefined)).not.toThrow();
    expect(b).toHaveBeenCalledTimes(1);
  });
});

describe('useEvent hook', () => {
  it('挂载订阅，卸载退订', () => {
    const fn = vi.fn();
    const { unmount } = renderHook(() => useEvent('models:changed', fn));
    act(() => emit('models:changed', undefined));
    expect(fn).toHaveBeenCalledTimes(1);
    unmount();
    act(() => emit('models:changed', undefined));
    expect(fn).toHaveBeenCalledTimes(1); // 没增加
  });
});

describe('useEmit hook', () => {
  it('返回 emit 函数', () => {
    const { result } = renderHook(() => useEmit<'models:changed'>());
    expect(typeof result.current).toBe('function');
    const fn = vi.fn();
    on('models:changed', fn);
    act(() => {
      result.current('models:changed', undefined);
    });
    expect(fn).toHaveBeenCalledTimes(1);
  });
});

describe('listEvents', () => {
  it('返回已订阅的事件名', () => {
    on('models:changed', () => {});
    on('tenant:switched', () => {});
    const names = listEvents();
    expect(names).toContain('models:changed');
    expect(names).toContain('tenant:switched');
  });
});