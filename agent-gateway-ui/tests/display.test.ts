/**
 * display.test.ts — 主题/密度切换
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDisplayPrefs } from '../src/hooks/useDisplayPrefs';

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
  document.documentElement.removeAttribute('data-density');
});

describe('useDisplayPrefs', () => {
  it('默认值为 light + comfortable', () => {
    const { result } = renderHook(() => useDisplayPrefs());
    expect(result.current.prefs.theme).toBe('light');
    expect(result.current.prefs.density).toBe('comfortable');
  });

  it('setTheme 写入 localStorage + 设置 data-theme', () => {
    const { result } = renderHook(() => useDisplayPrefs());
    act(() => {
      result.current.setTheme('dark');
    });
    expect(localStorage.getItem('agent-gateway.displayPrefs')).toContain('"theme":"dark"');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('setDensity 写入 localStorage + 设置 data-density', () => {
    const { result } = renderHook(() => useDisplayPrefs());
    act(() => {
      result.current.setDensity('compact');
    });
    expect(localStorage.getItem('agent-gateway.displayPrefs')).toContain('"density":"compact"');
    expect(document.documentElement.getAttribute('data-density')).toBe('compact');
  });

  it('读取 localStorage 中的旧偏好', () => {
    localStorage.setItem(
      'agent-gateway.displayPrefs',
      JSON.stringify({ theme: 'dark', density: 'compact' }),
    );
    const { result } = renderHook(() => useDisplayPrefs());
    expect(result.current.prefs.theme).toBe('dark');
    expect(result.current.prefs.density).toBe('compact');
  });

  it('损坏的 localStorage 不抛异常，回退默认', () => {
    localStorage.setItem('agent-gateway.displayPrefs', '{invalid');
    const { result } = renderHook(() => useDisplayPrefs());
    expect(result.current.prefs.theme).toBe('light');
  });

  it('system 主题根据 matchMedia 解析', () => {
    // 模拟 dark 偏好
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: () => ({
        matches: true,
        addEventListener: () => {},
        removeEventListener: () => {},
      }),
    });
    localStorage.setItem(
      'agent-gateway.displayPrefs',
      JSON.stringify({ theme: 'system', density: 'comfortable' }),
    );
    const { result } = renderHook(() => useDisplayPrefs());
    // theme 是 source，data-theme 是解析结果
    expect(result.current.prefs.theme).toBe('system');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});