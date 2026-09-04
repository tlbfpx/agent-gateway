/**
 * useOnboarding — 首启引导状态机
 *
 * 状态：
 *  - 'idle'       未触发（用户已关闭或已完成）
 *  - 'active'     引导进行中
 *  - 'completed'  全部步骤完成
 *
 * 持久化：localStorage 'agent-gateway.onboarding'
 * 完成条件（heuristic，可被向导的"完成按钮"覆写）：
 *  - localStorage 中存在 apiKey
 *  - models 列表非空（>=1 个 enabled）
 *  - chat 历史非空
 */
import { useEffect, useState, useCallback } from 'react';
import { getApiKey } from '../lib/request';

export type OnboardingStatus = 'idle' | 'active' | 'completed';

const KEY = 'agent-gateway.onboarding';

interface Persisted {
  status: OnboardingStatus;
  /** 已完成的步骤索引数组 */
  doneSteps: number[];
  /** 当前所在步骤索引 */
  current: number;
  /** 是否手动跳过 */
  skipped: boolean;
}

const DEFAULT: Persisted = { status: 'active', doneSteps: [], current: 0, skipped: false };

function read(): Persisted {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return DEFAULT;
    return { ...DEFAULT, ...JSON.parse(raw) };
  } catch {
    return DEFAULT;
  }
}

function write(p: Persisted) {
  try {
    localStorage.setItem(KEY, JSON.stringify(p));
  } catch {
    /* silent */
  }
}

export interface OnboardingStep {
  key: string;
  title: string;
  description: string;
  /** 跳转路径 */
  to: string;
  /** 检测该步完成的回调（返回 true = 完成） */
  detect?: () => Promise<boolean>;
}

export function useOnboarding(steps: OnboardingStep[]) {
  const [state, setState] = useState<Persisted>(DEFAULT);
  const [autoDetect, setAutoDetect] = useState(false);

  useEffect(() => {
    setState(read());
  }, []);

  // 自动检测：用户进入页面后，检测已完成步骤
  useEffect(() => {
    if (!autoDetect || state.status !== 'active') return;
    (async () => {
      let changed = false;
      const nextDone = [...state.doneSteps];
      for (let i = 0; i < steps.length; i++) {
        if (nextDone.includes(i)) continue;
        const step = steps[i];
        if (!step.detect) continue;
        try {
          if (await step.detect()) {
            nextDone.push(i);
            changed = true;
          }
        } catch {
          /* 检测失败不阻塞 */
        }
      }
      if (changed) {
        setState((prev) => {
          const next = { ...prev, doneSteps: nextDone };
          if (nextDone.length >= steps.length) {
            next.status = 'completed';
          } else {
            // 跳到第一个未完成的步骤
            next.current = steps.findIndex((_, i) => !nextDone.includes(i));
          }
          write(next);
          return next;
        });
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoDetect, state.status]);

  const next = useCallback(() => {
    setState((prev) => {
      const doneSteps = prev.doneSteps.includes(prev.current)
        ? prev.doneSteps
        : [...prev.doneSteps, prev.current];
      const nextIdx = prev.current + 1;
      const status: OnboardingStatus =
        nextIdx >= steps.length ? 'completed' : 'active';
      const cur = nextIdx >= steps.length ? prev.current : nextIdx;
      const nextState = { ...prev, current: cur, status, doneSteps };
      write(nextState);
      return nextState;
    });
  }, [steps.length]);

  const prev = useCallback(() => {
    setState((p) => {
      const nextState = { ...p, current: Math.max(0, p.current - 1) };
      write(nextState);
      return nextState;
    });
  }, []);

  const skip = useCallback(() => {
    setState((p) => {
      const nextState = { ...p, status: 'completed' as const, skipped: true };
      write(nextState);
      return nextState;
    });
  }, []);

  const restart = useCallback(() => {
    const fresh: Persisted = { status: 'active', doneSteps: [], current: 0, skipped: false };
    setState(fresh);
    write(fresh);
  }, []);

  /** 由 onboarding UI 在 mount 时调用，启动自动检测 */
  const enableAutoDetect = useCallback(() => setAutoDetect(true), []);

  return {
    state,
    currentStep: steps[state.current] ?? steps[0],
    isActive: state.status === 'active' && !state.skipped,
    isCompleted: state.status === 'completed',
    next,
    prev,
    skip,
    restart,
    enableAutoDetect,
  };
}

/** 检测 API Key 是否已设置 */
export function detectApiKey(): boolean {
  // 直读 localStorage：getApiKey 现有"未配置时预填演示 key"的副作用，
  // 引导检测要判断的是用户是否主动配置过，须绕开预填
  return Boolean(localStorage.getItem('agent-gateway.apiKey'));
}

/** 异步检测：models 列表非空（>0） */
export async function detectModelsReady(): Promise<boolean> {
  try {
    const { listModels } = await import('../lib/api/models');
    const ms = await listModels();
    return ms.some((m) => m.enabled);
  } catch {
    return false;
  }
}