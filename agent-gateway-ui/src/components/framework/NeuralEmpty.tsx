/**
 * NeuralEmpty — AI 风格空态占位图
 *
 * 替代 antd 默认 Empty 的"文件+对话框"：用 SVG 神经网络图形
 * （三层节点 + 连线 + 呼吸脉冲动画），呼应产品 AI 属性。
 * 连线上有缓慢流动的数据光点（circle + animateMotion）。
 */
import type { ReactNode } from 'react';

interface NeuralEmptyProps {
  description?: ReactNode;
  action?: ReactNode;
  /** 图形主题色，默认琥珀 */
  tone?: 'amber' | 'blue';
  size?: number;
}

const NODES: { x: number; y: number; layer: number }[] = [
  // 输入层（3 节点）
  { x: 16, y: 30, layer: 0 },
  { x: 16, y: 60, layer: 0 },
  { x: 16, y: 90, layer: 0 },
  // 隐藏层（4 节点）
  { x: 60, y: 20, layer: 1 },
  { x: 60, y: 47, layer: 1 },
  { x: 60, y: 73, layer: 1 },
  { x: 60, y: 100, layer: 1 },
  // 输出层（2 节点）
  { x: 104, y: 42, layer: 2 },
  { x: 104, y: 78, layer: 2 },
];

const EDGES: [number, number][] = (() => {
  const e: [number, number][] = [];
  NODES.forEach((n, i) => {
    NODES.forEach((m, j) => {
      if (m.layer === n.layer + 1) e.push([i, j]);
    });
  });
  return e;
})();

export function NeuralEmpty({
  description,
  action,
  tone = 'amber',
  size = 130,
}: NeuralEmptyProps) {
  const stroke = tone === 'blue' ? 'var(--ant-primary)' : 'var(--brand-amber)';
  const dotFill = tone === 'blue' ? '#69B1FF' : '#E0B68A';

  return (
    <div className="neural-empty" style={{ width: size }}>
      <svg
        viewBox="0 0 120 120"
        width={size}
        height={size * 0.92}
        aria-hidden="true"
        style={{ overflow: 'visible', display: 'block', margin: '0 auto' }}
      >
        {EDGES.map(([i, j], k) => (
          <line
            key={k}
            x1={NODES[i].x}
            y1={NODES[i].y}
            x2={NODES[j].x}
            y2={NODES[j].y}
            stroke={stroke}
            strokeWidth="0.8"
            strokeOpacity="0.28"
          />
        ))}
        {NODES.map((n, i) => (
          <g key={i}>
            <circle
              cx={n.x}
              cy={n.y}
              r={n.layer === 2 ? 4.6 : 3.6}
              fill="var(--bg-surface, #fff)"
              stroke={stroke}
              strokeWidth="1.4"
              className={n.layer === 2 ? 'neural-node neural-node--out' : 'neural-node'}
              style={{ animationDelay: `${i * 0.32}s` }}
            />
          </g>
        ))}
        {/* 数据流光点：沿两条代表性连线流动 */}
        <circle r="1.8" fill={dotFill}>
          <animateMotion dur="2.6s" repeatCount="indefinite" path="M16,30 L60,47 L104,42" />
        </circle>
        <circle r="1.8" fill={dotFill} opacity="0.8">
          <animateMotion dur="3.4s" begin="1.1s" repeatCount="indefinite" path="M16,90 L60,73 L104,78" />
        </circle>
      </svg>
      {description && <div className="neural-empty-desc">{description}</div>}
      {action && <div style={{ marginTop: 14, display: 'flex', justifyContent: 'center' }}>{action}</div>}
    </div>
  );
}
