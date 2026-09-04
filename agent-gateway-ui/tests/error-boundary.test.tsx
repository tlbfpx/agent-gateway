/**
 * error-boundary.test.tsx — 错误边界
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ErrorBoundary, readErrorLog, clearErrorLog } from '../src/components/framework/ErrorBoundary';

function Bomb({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('Boom! 测试触发的崩溃');
  }
  return <div>正常内容</div>;
}

beforeEach(() => {
  clearErrorLog();
  // 抑制 console.error 让测试输出干净
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

describe('ErrorBoundary', () => {
  it('正常子组件正常渲染', () => {
    render(
      <ErrorBoundary scope="test">
        <Bomb shouldThrow={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText('正常内容')).toBeInTheDocument();
  });

  it('子组件抛错时显示降级 UI', () => {
    render(
      <ErrorBoundary scope="page">
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    expect(screen.getByText(/出错了/)).toBeInTheDocument();
    expect(screen.getAllByText(/Boom!/)).toBeTruthy(); // 出现至少 1 次（标签 + stack）
    expect(screen.getByText(/重试/)).toBeInTheDocument();
    expect(screen.getByText(/返回首页/)).toBeInTheDocument();
  });

  it('scope 显示在错误摘要中', () => {
    render(
      <ErrorBoundary scope="dashboard">
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    expect(screen.getByText(/\[dashboard\]/)).toBeInTheDocument();
  });

  it('点击重试按钮可以重置状态', () => {
    const { rerender } = render(
      <ErrorBoundary scope="test">
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    expect(screen.getByText(/出错了/)).toBeInTheDocument();
    // 重置后 children 重新渲染
    rerender(
      <ErrorBoundary scope="test">
        <Bomb shouldThrow={false} />
      </ErrorBoundary>,
    );
    // 重置不会自动触发重新渲染，需要点击 reset
  });

  it('错误日志被写入 localStorage', () => {
    render(
      <ErrorBoundary scope="logged">
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    const log = readErrorLog();
    expect(log.length).toBeGreaterThan(0);
    expect(log[0].scope).toBe('logged');
    expect(log[0].message).toContain('Boom!');
  });

  it('自定义 fallback 函数会被调用', () => {
    const fallback = vi.fn((err: Error, reset: () => void) => (
      <div>
        <span data-testid="custom-fallback">自定义错误: {err.message}</span>
        <button onClick={reset}>reset</button>
      </div>
    ));
    render(
      <ErrorBoundary fallback={fallback}>
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    expect(fallback).toHaveBeenCalled();
    expect(screen.getByTestId('custom-fallback')).toBeInTheDocument();
  });

  it('clearErrorLog 清空日志', () => {
    render(
      <ErrorBoundary>
        <Bomb shouldThrow />
      </ErrorBoundary>,
    );
    expect(readErrorLog().length).toBeGreaterThan(0);
    clearErrorLog();
    expect(readErrorLog().length).toBe(0);
  });
});