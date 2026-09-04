import type { ReactNode } from 'react';
import { ArrowUpOutlined, ArrowDownOutlined, MinusOutlined } from '@ant-design/icons';
import { AnimatedNumber } from '../../hooks/useCountUp';

/**
 * MicronIcon — 主题色微图标（16px 线性风格，AI 神经/数据语义）
 * 替代原 ◐ ⚷ ≋ 等字符图标：视觉统一、可控描边、随 accent 变色。
 */
export function MicronIcon({ kind }: { kind: 'pulse' | 'key' | 'alert' | 'speed' | 'coins' | 'bolt' | 'gauge' }) {
  const common = {
    width: 15,
    height: 15,
    viewBox: '0 0 16 16',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.4,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    style: { flexShrink: 0 },
  };
  switch (kind) {
    case 'pulse': // 心电/脉冲 — 调用量
      return (
        <svg {...common}>
          <path d="M1 8h3l2-5 3 10 2-5h4" />
        </svg>
      );
    case 'key': // 密钥
      return (
        <svg {...common}>
          <circle cx="5" cy="8" r="3" />
          <path d="M8 8h7M12 8v2.4M14.5 8v1.6" />
        </svg>
      );
    case 'alert': // 三角警示
      return (
        <svg {...common}>
          <path d="M8 2 14.5 13.5H1.5Z" />
          <path d="M8 6.4v3.2M8 11.6v.2" />
        </svg>
      );
    case 'speed': // 仪表盘
      return (
        <svg {...common}>
          <path d="M2 12a6.5 6.5 0 1 1 12 0" />
          <path d="M8 12 11 7.5" />
          <circle cx="8" cy="12" r=".8" fill="currentColor" stroke="none" />
        </svg>
      );
    case 'coins': // 双币 — 成本
      return (
        <svg {...common}>
          <ellipse cx="6" cy="5.5" rx="4" ry="2" />
          <path d="M2 5.5v3c0 1.1 1.8 2 4 2s4-.9 4-2v-3" />
          <path d="M10 6.8c2 .2 4 1 4 2.2v3c0 1.1-1.8 2-4 2s-4-.9-4-2v-.6" />
        </svg>
      );
    case 'bolt': // 闪电 — 调用/能量
      return (
        <svg {...common}>
          <path d="M9 1.5 3.5 9H7l-1 5.5L11.5 7H8Z" />
        </svg>
      );
    case 'gauge': // 半环仪表 — 配额利用率
      return (
        <svg {...common}>
          <path d="M2.5 11.5a5.5 5.5 0 1 1 11 0" />
          <path d="M8 11.5 10.8 7.8" />
        </svg>
      );
  }
}

interface StatCardProps {
  label: ReactNode;
  value: ReactNode;
  /** 趋势描述，如 "▲ 2 · 较昨日" */
  trend?: { direction: 'up' | 'down' | 'flat'; text: string };
  /** 迷你趋势线（24 点，0-1 归一化）；提供时渲染微 sparkline */
  spark?: number[];
  /** 卡片主题色（用于光晕与线色），默认琥珀 */
  accent?: 'amber' | 'blue' | 'green' | 'red';
  /** 入场延迟（ms），用于多卡 stagger 编排 */
  delay?: number;
}
const ACCENT: Record<string, { glow: string; line: string; fill: string }> = {
  amber: {
    glow: 'rgba(212, 165, 116, .35)',
    line: 'var(--brand-amber)',
    fill: 'rgba(212, 165, 116, .16)',
  },
  blue: { glow: 'rgba(22, 119, 255, .35)', line: 'var(--ant-primary)', fill: 'rgba(22, 119, 255, .14)' },
  green: { glow: 'rgba(82, 196, 26, .32)', line: 'var(--ant-success)', fill: 'rgba(82, 196, 26, .13)' },
  red: { glow: 'rgba(255, 77, 79, .32)', line: 'var(--ant-error)', fill: 'rgba(255, 77, 79, .12)' },
};

/**
 * StatCard — 仪表盘统计卡片（AI 感版）
 * - 悬停浮起 + 主题色光晕（box-shadow glow）
 * - 数值等宽 tabular-nums + 微入场动画（数字从下方淡入）
 * - 可选 spark：32 点 SVG 迷你趋势线（带渐变填充）
 */
export function StatCard({ label, value, trend, spark, accent = 'amber', delay }: StatCardProps) {
  const a = ACCENT[accent];
  return (
    <div className={`stat-card stat-card--${accent}`} style={delay != null ? { animationDelay: `${delay}ms` } : undefined}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">
        {typeof value === 'string' ? <AnimatedNumber text={value} /> : value}
      </div>
      {trend && (
        <div className={`stat-trend ${trend.direction}`}>
          {trend.direction === 'up' && <ArrowUpOutlined />}
          {trend.direction === 'down' && <ArrowDownOutlined />}
          {trend.direction === 'flat' && <MinusOutlined />}
          <span>{trend.text}</span>
        </div>
      )}
      {spark && spark.length > 1 && <Sparkline points={spark} color={a.line} fill={a.fill} />}
    </div>
  );
}

/**
 * Sparkline — 零依赖 SVG 迷你趋势线。
 * points: 0-1 归一化数组；带渐变填充 + 末端呼吸圆点。
 * 全零数据时渲染底部基线（保持卡片结构完整 + 呼吸点暗示"等待数据"）。
 */
function Sparkline({ points, color, fill }: { points: number[]; color: string; fill: string }) {
  const W = 120;
  const H = 34;
  const max = Math.max(...points, 0.0001);
  const step = W / (points.length - 1);
  const coords = points.map((p, i) => [i * step, H - 2 - (p / max) * (H - 6)] as const);
  const path = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${path} L${W},${H} L0,${H} Z`;
  const last = coords[coords.length - 1];
  const gid = `sp-${Math.abs(hashStr(String(color) + points.length))}`;
  const isFlat = points.every((p) => p === 0);

  return (
    <svg
      className="stat-spark"
      width={W}
      height={H}
      viewBox={`0 0 ${W} ${H}`}
      aria-hidden="true"
      style={{ overflow: 'visible' }}
    >
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={fill} />
          <stop offset="100%" stopColor="transparent" />
        </linearGradient>
      </defs>
      {!isFlat && <path d={area} fill={`url(#${gid})`} />}
      <path
        d={path}
        fill="none"
        stroke={color}
        strokeWidth={isFlat ? 1 : 1.5}
        strokeLinejoin="round"
        strokeLinecap="round"
        opacity={isFlat ? 0.4 : 1}
      />
      <circle className="stat-spark-dot" cx={last[0]} cy={last[1]} r="2.5" fill={color} />
    </svg>
  );
}

function hashStr(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}
