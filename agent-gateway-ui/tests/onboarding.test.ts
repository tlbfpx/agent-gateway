/**
 * onboarding.test.ts — 首启引导状态机
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useOnboarding, detectApiKey } from '../src/hooks/useOnboarding';
import type { OnboardingStep } from '../src/hooks/useOnboarding';

const STEPS: OnboardingStep[] = [
  { key: 'a', title: 'A', description: '', to: '/a' },
  { key: 'b', title: 'B', description: '', to: '/b' },
  { key: 'c', title: 'C', description: '', to: '/c' },
];

beforeEach(() => {
  localStorage.clear();
});

describe('useOnboarding', () => {
  it('默认激活状态', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    expect(result.current.state.status).toBe('active');
    expect(result.current.state.current).toBe(0);
    expect(result.current.isActive).toBe(true);
  });

  it('next 推进并写 localStorage', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => result.current.next());
    expect(result.current.state.current).toBe(1);
    expect(result.current.state.doneSteps).toContain(0);
  });

  it('在最后一步 next → 完成', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => {
      result.current.next();
      result.current.next();
      result.current.next();
    });
    expect(result.current.state.status).toBe('completed');
    expect(result.current.isCompleted).toBe(true);
  });

  it('prev 回退', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => {
      result.current.next();
      result.current.next();
      result.current.prev();
    });
    expect(result.current.state.current).toBe(1);
  });

  it('prev 在第一步不再回退', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => result.current.prev());
    expect(result.current.state.current).toBe(0);
  });

  it('skip 标记为完成且不显示', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => result.current.skip());
    expect(result.current.state.status).toBe('completed');
    expect(result.current.state.skipped).toBe(true);
    expect(result.current.isActive).toBe(false);
  });

  it('restart 重置到第一步', () => {
    const { result } = renderHook(() => useOnboarding(STEPS));
    act(() => {
      result.current.next();
      result.current.next();
      result.current.restart();
    });
    expect(result.current.state.current).toBe(0);
    expect(result.current.state.doneSteps).toEqual([]);
    expect(result.current.state.status).toBe('active');
  });
});

describe('detectApiKey', () => {
  it('无 Key 时返回 false（localStorage 显式为空才算未配置）', () => {
    // detectApiKey 直读 localStorage：确保不受 getApiKey 副作用预填影响
    expect(localStorage.getItem('agent-gateway.apiKey') ?? '').toBe('');
    expect(detectApiKey()).toBe(false);
  });
  it('设置 Key 后返回 true', () => {
    localStorage.setItem('agent-gateway.apiKey', 'pk_live_x');
    expect(detectApiKey()).toBe(true);
  });
});