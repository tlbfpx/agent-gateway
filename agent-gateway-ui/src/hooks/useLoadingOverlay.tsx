/**
 * useLoadingOverlay — 全屏 Loading 状态机
 *
 * 用于：
 *  - 首次进入仪表盘等待大量数据
 *  - 配置发布 / 回滚等高耗时操作
 *  - 跨多个端点的批量操作
 *
 * 设计：
 *  - 单例 store（zustand 风格）
 *  - 多任务计数（refCount），避免竞争
 *  - 可选进度条 0-100
 *  - 200ms 后才显示（防闪烁）
 */
import { useEffect, useState, useCallback } from 'react';
import { Spin, Progress } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';

interface LoadingState {
  visible: boolean;
  message: string;
  progress?: number;
}

let _state: LoadingState = { visible: false, message: '' };
let _refCount = 0;
let _timer: ReturnType<typeof setTimeout> | null = null;
const _subs = new Set<(s: LoadingState) => void>();

function commit() {
  _subs.forEach((fn) => fn(_state));
}

/** 开始一个 Loading 任务；返回 release 函数 */
export function startLoading(message = '加载中…', progress?: number): () => void {
  _refCount++;
  if (_timer) clearTimeout(_timer);
  _state = { visible: false, message, progress };
  commit();
  _timer = setTimeout(() => {
    if (_refCount > 0) {
      _state = { ..._state, visible: true };
      commit();
    }
  }, 200);
  return () => {
    _refCount = Math.max(0, _refCount - 1);
    if (_refCount === 0) {
      if (_timer) clearTimeout(_timer);
      _state = { visible: false, message: '', progress: undefined };
      commit();
    }
  };
}

/** 更新当前任务的进度 */
export function updateProgress(progress: number, message?: string) {
  if (_refCount === 0) return;
  _state = {
    visible: true,
    message: message ?? _state.message,
    progress: Math.max(0, Math.min(100, progress)),
  };
  commit();
}

/** React Hook：订阅状态 */
export function useLoadingState(): LoadingState {
  const [s, set] = useState(_state);
  useEffect(() => {
    const fn = (v: LoadingState) => set(v);
    _subs.add(fn);
    return () => {
      _subs.delete(fn);
    };
  }, []);
  return s;
}

/** 高阶封装：把一个 Promise 包装在 Loading 中 */
export async function withLoading<T>(
  fn: () => Promise<T>,
  message = '加载中…',
): Promise<T> {
  const release = startLoading(message);
  try {
    return await fn();
  } finally {
    release();
  }
}

/** 全屏 Loading 渲染组件 */
export function LoadingOverlay() {
  const s = useLoadingState();
  if (!s.visible) return null;
  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: 'rgba(15, 27, 61, 0.55)',
        backdropFilter: 'blur(2px)',
        zIndex: 2000,
        display: 'grid',
        placeItems: 'center',
        animation: 'fadeIn 200ms ease-out',
      }}
      role="progressbar"
      aria-label={s.message}
    >
      <div
        style={{
          background: 'var(--bg-surface)',
          padding: '32px 40px',
          borderRadius: 12,
          boxShadow: '0 16px 48px rgba(0,0,0,0.25)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 16,
          minWidth: 280,
        }}
      >
        <Spin indicator={<LoadingOutlined spin />} size="large" />
        <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-1)' }}>
          {s.message || '加载中…'}
        </div>
        {s.progress != null && (
          <div style={{ width: '100%' }}>
            <Progress
              percent={Math.round(s.progress)}
              size="small"
              strokeColor="var(--brand-amber)"
              showInfo
            />
          </div>
        )}
      </div>
    </div>
  );
}

/** 模拟多步骤进度（演示用） */
export function useStepLoader() {
  const [step, setStep] = useState(0);
  const [total, setTotal] = useState(0);

  const begin = useCallback((t: number) => {
    setStep(0);
    setTotal(t);
  }, []);

  const tick = useCallback(() => {
    setStep((s) => {
      const next = s + 1;
      updateProgress((next / total) * 100);
      return next;
    });
  }, [total]);

  return { step, total, begin, tick };
}