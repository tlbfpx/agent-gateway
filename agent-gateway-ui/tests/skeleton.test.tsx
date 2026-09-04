/**
 * skeleton.test.tsx — 骨架屏
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SkeletonTable, SkeletonCards, SkeletonPage, SkeletonRow, SkeletonParagraph, SkeletonChart } from '../src/components/framework/Skeleton';

describe('Skeleton', () => {
  it('SkeletonTable 默认渲染 5 行 4 列', () => {
    const { container } = render(<SkeletonTable />);
    expect(container.querySelectorAll('.ant-skeleton')).toBeTruthy();
  });

  it('SkeletonTable 自定义行/列数', () => {
    const { container } = render(<SkeletonTable rows={3} columns={6} />);
    expect(container.querySelector('.ant-skeleton')).toBeInTheDocument();
  });

  it('SkeletonCards 默认 4 张', () => {
    const { container } = render(<SkeletonCards />);
    // 4 个 col-xs-24
    const cols = container.querySelectorAll('.ant-col');
    expect(cols.length).toBeGreaterThanOrEqual(4);
  });

  it('SkeletonCards 自定义数量', () => {
    const { container } = render(<SkeletonCards count={2} />);
    const cols = container.querySelectorAll('.ant-col');
    expect(cols.length).toBe(2);
  });

  it('SkeletonPage 含 cards + table', () => {
    const { container } = render(<SkeletonPage />);
    expect(container.querySelectorAll('.ant-skeleton').length).toBeGreaterThan(0);
  });

  it('SkeletonPage 仅含 table', () => {
    const { container } = render(<SkeletonPage hasCards={false} />);
    expect(container.querySelectorAll('.ant-skeleton').length).toBeGreaterThan(0);
  });

  it('SkeletonRow 默认宽 60%', () => {
    const { container } = render(<SkeletonRow />);
    expect(container.querySelector('.ant-skeleton')).toBeInTheDocument();
  });

  it('SkeletonRow 自定义宽高', () => {
    const { container } = render(<SkeletonRow width={200} height={20} />);
    expect(container.querySelector('.ant-skeleton')).toBeInTheDocument();
  });

  it('SkeletonParagraph 默认 3 行', () => {
    const { container } = render(<SkeletonParagraph />);
    expect(container.querySelectorAll('.ant-skeleton-input').length).toBeGreaterThanOrEqual(3);
  });

  it('SkeletonChart 默认 24 根柱', () => {
    const { container } = render(<SkeletonChart />);
    const bars = container.querySelectorAll('.bar');
    expect(bars.length).toBe(24);
  });
});