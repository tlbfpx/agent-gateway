/**
 * Skeleton — 骨架屏（感知性能优化）
 *
 * 设计目标：替换 antd Spin 的"等转圈"，改为"内容预占位"
 * - SkeletonTable: 表格骨架（5 行 × N 列）
 * - SkeletonCards: 4 张 StatCard 骨架
 * - SkeletonPage: 整页骨架（标题 + 4 卡 + 2 块）
 * - SkeletonRow: 单行文本骨架
 *
 * 颜色：CSS 变量 --bg-sunken + 微动画，与品牌一致
 */
import { Skeleton, Space, Row, Col } from 'antd';

const SHIMMER = 'var(--bg-sunken)';

interface SkeletonTableProps {
  rows?: number;
  columns?: number;
}

export function SkeletonTable({ rows = 5, columns = 4 }: SkeletonTableProps) {
  return (
    <div
      style={{
        background: 'var(--bg-surface)',
        borderRadius: 'var(--r-lg)',
        padding: 16,
      }}
    >
      {/* header */}
      <Skeleton.Input
        active
        size="small"
        style={{ width: '100%', height: 32, marginBottom: 16, background: SHIMMER }}
      />
      {Array.from({ length: rows }).map((_, i) => (
        <div
          key={i}
          style={{
            display: 'flex',
            gap: 16,
            padding: '12px 0',
            borderBottom: '1px dashed var(--border-thin)',
          }}
        >
          {Array.from({ length: columns }).map((__, j) => (
            <Skeleton.Input
              key={j}
              active
              size="small"
              style={{
                flex: j === 0 ? 1 : j === 1 ? 2 : 1,
                height: 16,
                background: SHIMMER,
              }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

interface SkeletonCardsProps {
  count?: number;
}

export function SkeletonCards({ count = 4 }: SkeletonCardsProps) {
  const cols = count === 4 ? 6 : count === 3 ? 8 : 8;
  return (
    <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
      {Array.from({ length: count }).map((_, i) => (
        <Col xs={24} sm={12} md={cols} key={i}>
          <div
            style={{
              background: 'var(--bg-surface)',
              borderRadius: 'var(--r-lg)',
              padding: 16,
              border: '1px solid var(--border-thin)',
            }}
          >
            <Skeleton.Input
              active
              size="small"
              style={{ width: '40%', height: 12, marginBottom: 12, background: SHIMMER }}
            />
            <Skeleton.Input
              active
              size="large"
              style={{ width: '60%', height: 28, marginBottom: 8, background: SHIMMER }}
            />
            <Skeleton.Input
              active
              size="small"
              style={{ width: '30%', height: 10, background: SHIMMER }}
            />
          </div>
        </Col>
      ))}
    </Row>
  );
}

interface SkeletonPageProps {
  hasCards?: boolean;
  hasTable?: boolean;
}

export function SkeletonPage({ hasCards = true, hasTable = true }: SkeletonPageProps) {
  return (
    <div>
      {/* 顶部标题 */}
      <div style={{ marginBottom: 24 }}>
        <Skeleton.Input
          active
          size="small"
          style={{ width: 120, height: 10, marginBottom: 8, background: SHIMMER }}
        />
        <Skeleton.Input
          active
          size="large"
          style={{ width: 240, height: 28, marginBottom: 8, background: SHIMMER }}
        />
        <Skeleton.Input
          active
          size="small"
          style={{ width: 360, height: 12, background: SHIMMER }}
        />
      </div>
      {hasCards && <SkeletonCards />}
      {hasTable && <SkeletonTable />}
    </div>
  );
}

interface SkeletonRowProps {
  width?: number | string;
  height?: number;
}

export function SkeletonRow({ width = '60%', height = 14 }: SkeletonRowProps) {
  return (
    <Skeleton.Input
      active
      size="small"
      style={{ width, height, background: SHIMMER }}
    />
  );
}

/** 多行组合骨架（如描述性文本） */
export function SkeletonParagraph({ lines = 3 }: { lines?: number }) {
  return (
    <Space direction="vertical" size={6} style={{ width: '100%' }}>
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton.Input
          key={i}
          active
          size="small"
          style={{
            width: i === lines - 1 ? '70%' : '100%',
            height: 12,
            background: SHIMMER,
          }}
        />
      ))}
    </Space>
  );
}

/** Chart 骨架（占满高度的柱状条） */
export function SkeletonChart({ bars = 24 }: { bars?: number }) {
  const heights = Array.from({ length: bars }, (_, i) => 30 + ((i * 47) % 60));
  return (
    <div className="mini-chart" style={{ minHeight: 120 }}>
      {heights.map((h, i) => (
        <div
          key={i}
          className="bar"
          style={{
            height: `${h}%`,
            background: SHIMMER,
            opacity: 0.5,
          }}
        />
      ))}
    </div>
  );
}