/**
 * ErrorBoundary — React 错误边界
 *
 * - 捕获子组件渲染/生命周期中的错误
 * - 显示降级 UI：图标 + 标题 + 错误摘要 + 重试 + 返回首页
 * - 记录错误到 console + localStorage（简单日志）
 * - 支持嵌套（多个 ErrorBoundary 形成 fallback 链）
 *
 * 注意：React 错误边界必须是 class component，函数组件无法使用 componentDidCatch。
 */
import { Component, type ReactNode, type ErrorInfo } from 'react';
import { Button, Typography } from 'antd';
import { WarningOutlined, ReloadOutlined, HomeOutlined, BugOutlined } from '@ant-design/icons';

const { Title, Paragraph } = Typography;

interface Props {
  children: ReactNode;
  /** 自定义降级 UI 渲染函数（高级用法） */
  fallback?: (err: Error, reset: () => void) => ReactNode;
  /** 错误回调 */
  onError?: (err: Error, info: ErrorInfo) => void;
  /** 错误层级标签（用于日志） */
  scope?: string;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorInfo: ErrorInfo | null;
  /** 同一页面是否已重试过（避免无限刷新） */
  retried: boolean;
}

const LOG_KEY = 'agent-gateway.error.log';

interface LogEntry {
  scope: string;
  message: string;
  stack?: string;
  time: string;
  url: string;
}

function pushLog(entry: LogEntry) {
  try {
    const raw = localStorage.getItem(LOG_KEY);
    const list: LogEntry[] = raw ? JSON.parse(raw) : [];
    list.unshift(entry);
    localStorage.setItem(LOG_KEY, JSON.stringify(list.slice(0, 50)));
  } catch {
    /* silent */
  }
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null, errorInfo: null, retried: false };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    const scope = this.props.scope ?? 'app';
    pushLog({
      scope,
      message: error.message,
      stack: error.stack,
      time: new Date().toISOString(),
      url: typeof window !== 'undefined' ? window.location.pathname : '',
    });
    // eslint-disable-next-line no-console
    console.error(`[ErrorBoundary:${scope}]`, error, info);
    this.props.onError?.(error, info);
    this.setState({ errorInfo: info });
  }

  reset = () => {
    if (this.state.retried) {
      // 已经重试过：直接跳首页，避免无限刷新
      window.location.href = '/dashboard';
      return;
    }
    this.setState({ hasError: false, error: null, errorInfo: null, retried: true });
  };

  render() {
    if (this.state.hasError && this.state.error) {
      if (this.props.fallback) {
        return this.props.fallback(this.state.error, this.reset);
      }
      return <DefaultFallback error={this.state.error} reset={this.reset} scope={this.props.scope} />;
    }
    return this.props.children;
  }
}

function DefaultFallback({
  error,
  reset,
  scope,
}: {
  error: Error;
  reset: () => void;
  scope?: string;
}) {
  return (
    <div
      style={{
        minHeight: '60vh',
        display: 'grid',
        placeItems: 'center',
        padding: 24,
      }}
    >
      <div
        style={{
          maxWidth: 560,
          background: 'var(--bg-surface)',
          border: '1px solid var(--border-thin)',
          borderRadius: 12,
          padding: 32,
          textAlign: 'center',
          boxShadow: '0 4px 16px rgba(0,0,0,0.04)',
        }}
      >
        <WarningOutlined
          style={{
            fontSize: 48,
            color: 'var(--ant-error)',
            marginBottom: 16,
          }}
        />
        <Title level={3} style={{ marginTop: 0 }}>
          出错了
        </Title>
        <Paragraph type="secondary">
          组件渲染时发生异常。该区域已自动隔离，不会影响其他页面。
        </Paragraph>

        <div
          style={{
            background: 'var(--bg-sunken)',
            border: '1px solid var(--border-thin)',
            borderRadius: 6,
            padding: 12,
            margin: '16px 0',
            textAlign: 'left',
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            color: 'var(--ant-error)',
            maxHeight: 200,
            overflow: 'auto',
          }}
        >
          <div style={{ color: 'var(--text-2)', marginBottom: 6, fontWeight: 600 }}>
            <BugOutlined /> {scope ? `[${scope}] ` : ''}
            {error.message}
          </div>
          {error.stack && (
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {error.stack.split('\n').slice(0, 8).join('\n')}
            </pre>
          )}
        </div>

        <div style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 16 }}>
          <Button type="primary" icon={<ReloadOutlined />} onClick={reset}>
            重试
          </Button>
          <Button icon={<HomeOutlined />} onClick={() => (window.location.href = '/dashboard')}>
            返回首页
          </Button>
        </div>

        <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 16, marginBottom: 0 }}>
          错误已记录到本地日志（最多 50 条）。如持续出现请反馈 issue。
        </Paragraph>
      </div>
    </div>
  );
}

/** 读取错误日志（供调试页用） */
export function readErrorLog(): LogEntry[] {
  try {
    const raw = localStorage.getItem(LOG_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

/** 清空错误日志 */
export function clearErrorLog() {
  try {
    localStorage.removeItem(LOG_KEY);
  } catch {
    /* silent */
  }
}