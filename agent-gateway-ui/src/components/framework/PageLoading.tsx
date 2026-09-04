/**
 * PageLoading.tsx — 统一全页 Loading 占位（Round 11 §ui-b3）
 *
 * 动机：5 种 Loading 风格混用（Spin/Skeleton/EmptyState 切换/Table loading/自定义 div）
 * 收敛目标：所有"整页加载中"统一走本组件,内容区加载仍用 Table loading={true} 或 SkeletonPage
 *
 * 三种用法：
 *   <PageLoading />                          // 默认中央 Spin + 文字
 *   <PageLoading description="加载 traces..." />  // 自定义文案
 *   <PageLoading variant="skeleton" />       // 走骨架屏(Dashboard 风格)
 */

import { Spin } from 'antd';
import { SkeletonPage } from './Skeleton';

interface PageLoadingProps {
  description?: string;
  variant?: 'spin' | 'skeleton';
  /** 整页最小高度,默认 320;Modal 内可传 200 */
  minHeight?: number;
}

export function PageLoading({
  description = '加载中…',
  variant = 'spin',
  minHeight = 320,
}: PageLoadingProps) {
  if (variant === 'skeleton') {
    return <SkeletonPage />;
  }
  return (
    <div
      role="status"
      aria-label={description}
      style={{
        minHeight,
        display: 'grid',
        placeItems: 'center',
        padding: 48,
        textAlign: 'center',
      }}
    >
      <div style={{ display: 'inline-flex', alignItems: 'center', gap: 12, color: 'var(--text-3)' }}>
        <Spin />
        <span>{description}</span>
      </div>
    </div>
  );
}
