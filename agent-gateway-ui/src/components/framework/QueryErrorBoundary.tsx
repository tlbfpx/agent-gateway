/**
 * QueryErrorBoundary — 路由级数据错误边界（Round 10 B-2 / spec FE-ERR-001）
 *
 * 与 ErrorBoundary 的分工（按「具体错误类型」分发）：
 *
 *   AppShell
 *   └── ErrorBoundary scope="app"    ← 壳层崩溃（Sidebar/Header），全屏卡片 + 堆栈
 *       └── Content
 *           └── QueryErrorBoundary   ← 本组件：路由页面出错，整页 ErrorState + 原地重试
 *               └── <Outlet/>
 *
 * 降级 UI 复用 ErrorState —— 与列表页内联的「加载失败」红框长得一模一样，
 * 用户无需区分错误来自渲染期还是异步加载。
 *
 * 与 ErrorBoundary 的两点行为差异：
 *  1. 不做「重试过一次就跳首页」的限制。路由级场景里用户改了筛选条件再重试
 *     是完全合理的路径，强制跳转反而丢失上下文。
 *  2. 支持 resetKeys —— 路由 pathname 变化时自动清错误，避免用户被卡在
 *     上一个页面的错误态里出不来。
 *
 * 注意：React 错误边界只能捕获**渲染期**抛出的错误。异步 fetch 失败
 * （Promise reject）不会冒泡到这里，那条链路由 notifyError 负责。
 */
import { Component, type ReactNode, type ErrorInfo } from 'react';
import { ErrorState } from './EmptyState';
import { notifyError } from '../../lib/request';

interface Props {
  children: ReactNode;
  /** 自定义降级 UI，覆盖默认的整页 ErrorState */
  fallback?: ReactNode;
  /** 错误回调（日志上报等） */
  onError?: (err: Error, info: ErrorInfo) => void;
  /** 任一元素浅比较变化时自动 reset（典型用法：[pathname]） */
  resetKeys?: unknown[];
  /** 上下文标签，进通知中心的标题里 */
  context?: string;
}

interface State {
  error: Error | null;
}

function shallowDiff(a: unknown[] | undefined, b: unknown[] | undefined): boolean {
  if (a === b) return false;
  if (!a || !b) return true;
  if (a.length !== b.length) return true;
  return a.some((v, i) => !Object.is(v, b[i]));
}

export class QueryErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error('[QueryErrorBoundary]', error, info);
    // 错误同时进常驻通知中心，用户离开页面后仍可回看
    notifyError(error, this.props.context ?? '页面');
    this.props.onError?.(error, info);
  }

  componentDidUpdate(prev: Props) {
    if (this.state.error && shallowDiff(prev.resetKeys, this.props.resetKeys)) {
      this.reset();
    }
  }

  reset = () => {
    this.setState({ error: null });
  };

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (this.props.fallback !== undefined) return this.props.fallback;

    return (
      <div style={{ padding: 24, display: 'grid', placeItems: 'center', minHeight: '50vh' }}>
        <div style={{ width: '100%', maxWidth: 560 }}>
          <ErrorState error={error.message} onRetry={this.reset} retryLabel="重试" />
        </div>
      </div>
    );
  }
}
