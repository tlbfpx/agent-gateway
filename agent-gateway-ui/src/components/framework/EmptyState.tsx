/**
 * EmptyState — 错误 / 空状态统一组件
 * - ErrorState: 失败态，显示重试 + 错误摘要
 * - EmptyState: 空态，显示 CTA + 引导
 */
import { Empty, Button, Space } from 'antd';
import {
  InboxOutlined,
  WarningOutlined,
  ReloadOutlined,
  FileSearchOutlined,
} from '@ant-design/icons';
import { NeuralEmpty } from './NeuralEmpty';

interface EmptyStateProps {
  description?: string;
  icon?: React.ReactNode;
  /** CTA 按钮文案 + 回调 */
  action?: { label: string; onClick: () => void };
  /** 二级 CTA */
  secondaryAction?: { label: string; onClick: () => void };
  variant?: 'default' | 'empty' | 'no-result' | 'no-data';
}

const ICONS: Record<NonNullable<EmptyStateProps['variant']>, React.ReactNode> = {
  default: <InboxOutlined />,
  empty: <FileSearchOutlined />,
  'no-result': <FileSearchOutlined />,
  'no-data': <InboxOutlined />,
};

export function EmptyState({
  description = '暂无数据',
  icon,
  action,
  secondaryAction,
  variant = 'default',
}: EmptyStateProps) {
  return (
    <div style={{ padding: '24px 16px', textAlign: 'center' }}>
      <NeuralEmpty
        tone="amber"
        description={
          <span style={{ color: 'var(--text-3)', fontSize: 13 }}>{description}</span>
        }
        action={
          (action || secondaryAction) && (
            <Space>
              {action && (
                <Button type="primary" onClick={action.onClick}>
                  {action.label}
                </Button>
              )}
              {secondaryAction && (
                <Button onClick={secondaryAction.onClick}>{secondaryAction.label}</Button>
              )}
            </Space>
          )
        }
      />
      {/* 自定义 icon 场景退回 antd Empty（兼容旧调用方） */}
      {icon && (
        <Empty image={icon} imageStyle={{ height: 64, color: 'var(--text-4)' }} description={false} />
      )}
    </div>
  );
}

interface ErrorStateProps {
  error: string | Error;
  onRetry?: () => void;
  /** 重试按钮文案 */
  retryLabel?: string;
}

export function ErrorState({ error, onRetry, retryLabel = '重试' }: ErrorStateProps) {
  const msg = error instanceof Error ? error.message : error;
  return (
    <div
      style={{
        padding: '24px 16px',
        textAlign: 'center',
        background: 'rgba(255, 77, 79, 0.04)',
        border: '1px solid rgba(255, 77, 79, 0.25)',
        borderRadius: 8,
      }}
    >
      <WarningOutlined style={{ fontSize: 32, color: 'var(--ant-error)', marginBottom: 8 }} />
      <div style={{ color: 'var(--text-1)', fontSize: 14, marginBottom: 4, fontWeight: 500 }}>
        加载失败
      </div>
      <div
        className="mono"
        style={{
          color: 'var(--ant-error)',
          fontSize: 12,
          marginBottom: 12,
          maxWidth: 480,
          margin: '0 auto 12px',
          wordBreak: 'break-word',
        }}
      >
        {msg}
      </div>
      {onRetry && (
        <Button type="primary" icon={<ReloadOutlined />} onClick={onRetry}>
          {retryLabel}
        </Button>
      )}
    </div>
  );
}
