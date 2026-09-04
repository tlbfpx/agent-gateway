/**
 * ModelSharePie — 模型成本占比饼图（零依赖 SVG，Round 10）
 *
 * - 颜色取自 StatCard accent 色板（amber/blue/green/red/purple）保证品牌一致
 * - 中央总额数字 + hover 显示明细 Tooltip
 * - 自动合并 < 5% 的小份额为「其他」避免扇区过窄
 *
 * GW-UX-CEST-002
 */
import { useMemo } from 'react';
import { Tooltip } from 'antd';

export interface PieSlice {
  label: string;
  value: number;
  /** 可选颜色，未指定则从色板循环 */
  color?: string;
}

const PALETTE = [
  '#D4A574', // amber
  '#1677ff', // blue
  '#52c41a', // green
  '#ff4d4f', // red
  '#8B5CF6', // purple
  '#13c2c2', // cyan
  '#faad14', // gold
  '#eb2f96', // magenta
];

const SIZE = 220;
const RADIUS = 90;
const INNER_R = 56;

interface Segment {
  label: string;
  value: number;
  pct: number;
  color: string;
  startAngle: number;
  endAngle: number;
  path: string;
}

function polarToCartesian(cx: number, cy: number, r: number, angle: number) {
  const rad = ((angle - 90) * Math.PI) / 180;
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) };
}

function arcPath(cx: number, cy: number, r: number, ir: number, start: number, end: number) {
  const s = polarToCartesian(cx, cy, r, end);
  const e = polarToCartesian(cx, cy, r, start);
  const si = polarToCartesian(cx, cy, ir, end);
  const ei = polarToCartesian(cx, cy, ir, start);
  const large = end - start <= 180 ? 0 : 1;
  return [
    `M${s.x.toFixed(1)},${s.y.toFixed(1)}`,
    `A${r},${r} 0 ${large} 0 ${e.x.toFixed(1)},${e.y.toFixed(1)}`,
    `L${ei.x.toFixed(1)},${ei.y.toFixed(1)}`,
    `A${ir},${ir} 0 ${large} 1 ${si.x.toFixed(1)},${si.y.toFixed(1)}`,
    'Z',
  ].join(' ');
}

export interface ModelSharePieProps {
  slices: PieSlice[];
  total: number;
  title?: string;
  /** 百分比阈值：低于此值合并到"其他"(默认 0.05 = 5%) */
  mergeBelowPct?: number;
}

export function ModelSharePie({ slices, total, title = '模型占比', mergeBelowPct = 0.05 }: ModelSharePieProps) {
  const { segments, others } = useMemo(() => {
    if (slices.length === 0 || total <= 0) return { segments: [] as Segment[], others: [] as PieSlice[] };
    const sorted = [...slices].sort((a, b) => b.value - a.value);
    const main: PieSlice[] = [];
    const tail: PieSlice[] = [];
    for (const s of sorted) {
      const pct = s.value / total;
      if (pct < mergeBelowPct) tail.push(s);
      else main.push(s);
    }
    const merged: PieSlice[] = [...main];
    if (tail.length > 0) {
      merged.push({ label: `其他 · ${tail.length}`, value: tail.reduce((acc, s) => acc + s.value, 0) });
    }
    let cursor = 0;
    const segs: Segment[] = merged.map((s, i) => {
      const pct = s.value / total;
      const sweep = pct * 360;
      const start = cursor;
      const end = cursor + sweep;
      cursor = end;
      return {
        label: s.label,
        value: s.value,
        pct,
        color: s.color ?? PALETTE[i % PALETTE.length],
        startAngle: start,
        endAngle: end,
        path: arcPath(SIZE / 2, SIZE / 2, RADIUS, INNER_R, start, end),
      };
    });
    return { segments: segs, others: tail };
  }, [slices, total, mergeBelowPct]);

  const top = segments[0];

  return (
    <div className="model-share-pie" data-testid="model-share-pie" style={{ padding: '12px 16px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <strong style={{ fontSize: 13 }}>{title}</strong>
        <span style={{ fontSize: 11, color: 'var(--text-3)' }}>共 {slices.length} 个</span>
      </div>
      {segments.length === 0 ? (
        <div style={{ height: SIZE, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-3)' }}>
          暂无数据
        </div>
      ) : (
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
          <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true">
            {segments.map((s, i) => (
              <Tooltip
                key={i}
                title={
                  <div>
                    <div>{s.label}</div>
                    <div>¥{s.value.toFixed(2)} · {(s.pct * 100).toFixed(1)}%</div>
                  </div>
                }
              >
                <path d={s.path} fill={s.color} stroke="var(--bg-surface, #111)" strokeWidth="1.5" className="pie-slice">
                  <title>{`${s.label}: ¥${s.value.toFixed(2)} (${(s.pct * 100).toFixed(1)}%)`}</title>
                </path>
              </Tooltip>
            ))}
            {/* 中央总额 */}
            <text x={SIZE / 2} y={SIZE / 2 - 6} textAnchor="middle" fontSize="11" fill="var(--text-3)">
              总成本
            </text>
            <text x={SIZE / 2} y={SIZE / 2 + 14} textAnchor="middle" fontSize="16" fontWeight="700" fill="var(--text-1)">
              ¥{total.toFixed(0)}
            </text>
          </svg>
          {/* 图例 */}
          <div style={{ flex: 1, minWidth: 140 }}>
            {segments.map((s, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, padding: '3px 0' }}>
                <span style={{ width: 10, height: 10, background: s.color, borderRadius: 2, flexShrink: 0 }} />
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.label}</span>
                <span className="mono" style={{ color: 'var(--text-3)' }}>{(s.pct * 100).toFixed(0)}%</span>
              </div>
            ))}
            {top && others.length > 0 && (
              <div style={{ marginTop: 4, fontSize: 11, color: 'var(--text-3)' }}>
                + {others.length} 个模型 &lt; 5% 已合并
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}