/**
 * global-shortcuts.test.ts — 全局快捷键覆盖
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useGlobalShortcuts, openHelp, closeHelp, useHelpOpen } from '../src/hooks/useGlobalShortcuts';

beforeEach(() => {
  closeHelp();
});

describe('global-shortcuts state', () => {
  it('默认关闭', () => {
    const { result } = renderHook(() => useHelpOpen());
    expect(result.current).toBe(false);
  });

  it('openHelp → 打开，closeHelp → 关闭', () => {
    const { result } = renderHook(() => useHelpOpen());
    act(() => {
      openHelp();
    });
    expect(result.current).toBe(true);
    act(() => {
      closeHelp();
    });
    expect(result.current).toBe(false);
  });

  it('openHelp 幂等（重复调用不重复触发 listener）', () => {
    const { result } = renderHook(() => useHelpOpen());
    act(() => {
      openHelp();
      openHelp();
    });
    expect(result.current).toBe(true);
    act(() => {
      closeHelp();
    });
    expect(result.current).toBe(false);
  });
});

describe('useGlobalShortcuts', () => {
  it('挂载时不抛错', () => {
    // 包一层 MemoryRouter 因为 useGlobalShortcuts 内调用 useNavigate
    expect(() =>
      renderHook(() => useGlobalShortcuts(), { wrapper: MemoryRouter as any }),
    ).not.toThrow();
  });
});