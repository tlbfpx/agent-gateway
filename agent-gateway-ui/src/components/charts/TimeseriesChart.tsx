/**
 * TimeseriesChart — 单序列折线 + 渐变面积（成本曲线专用，零依赖 SVG）
 *
 * 复用 AreaBarChart 的视觉风格（Catmull-Rom 平滑 + 渐变填充 + 十字准线 + 气泡），
 * 但只保留单序列 + 不渲染柱层（避免与"成本曲线"语义重复 — 柱交给 PeriodCompareBar）。
 *
 * Round 10 / GW-UX-CEST-001
 */
import { useMemo, useRef, useState } from 'react';
import { Tooltip } from 'antd';
import type { BillingTimeseriesPoint } from '../../lib/api/cost';

export interface TimeseriesPoint {
  /** X 轴标签（YYYY-MM-DD 或 MM-DD） */
  t: string;
  /** 数值（成本元 / 调用次数 / tokens） */
  n: number;
}

const W = 640;
const H = 200;
const PAD_L = 36;
const PAD_R = 12;
const PAD_T = 12;
const PAD_B = 24;

function smoothPath(pts: readonly [number, number][], close: boolean): string {
  if (pts.length < 2) return '';
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[Math.min(pts.length - 1, i + 2)];
    const c1x = p1[0] + (p2[0] - p0[0]) / 6;
    const c1y = p1[1] + (p2[1] - p0[1]) / 6;
    const c2x = p2[0] - (p3[0] - p1[0]) / 6;
    const c2y = p2[1] - (p3[1] - p1[1]) / 6;
    d += ` C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
  }
  if (close) {
    d += ` L${pts[pts.length - 1][0]},${H - PAD_B} L${pts[0][0]},${H - PAD_B} Z`;
  }
  return d;
}

export interface TimeseriesChartProps {
  /** 兼容：TimeseriesPoint[]（旧） */
  points?: TimeseriesPoint[];
  /** 推荐：BillingTimeseriesPoint[]（cost.ts），自动转 t/n */
  data?: BillingTimeseriesPoint[];
  /** 渲染指标：'cost' | 'calls'（data 模式下用） */
  metric?: 'cost' | 'calls';
  title: string;
  unit: string;
  range?: string;
  empty?: boolean;
  /** 数值格式化函数（默认 toFixed(2)） */
  formatValue?: (n: number) => string;
  /** 标签稀疏度：每 N 个点显示一个 X 轴标签（默认 7） */
  labelStride?: number;
}

export function TimeseriesChart({ points: rawPoints, data: rawData, metric = 'cost', title, unit, formatValue, labelStride = 7, empty }: TimeseriesChartProps) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);

  const points: TimeseriesPoint[] = useMemo(() => {
    if (rawPoints) return rawPoints;
    if (!rawData) return [];
    return rawData.map((p) => ({ t: p.date, n: metric === 'cost' ? p.costCny : p.calls }));
  }, [rawPoints, rawData, metric]);

  const fmt = formatValue ?? ((n: number) => n.toFixed(2));

  const { coords, linePath, areaPath, max } = useMemo(() => {
    const max = Math.max(1, ...points.map((p) => p.n));
    const innerW = W - PAD_L - PAD_R;
    const innerH = H - PAD_T - PAD_B;
    const step = points.length > 1 ? innerW / (points.length - 1) : innerW;
    const coords: [number, number][] = points.map((p, i) => [
      +(PAD_L + i * step).toFixed(1),
      +(H - PAD_B - (p.n / max) * (innerH - 4)).toFixed(1),
    ]);
    return {
      coords,
      linePath: smoothPath(coords, false),
      areaPath: smoothPath(coords, true),
      max,
    };
  }, [points]);

  const onMove = (e: React.MouseEvent) => {
    const el = wrapRef.current;
    if (!el || points.length < 2) return;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    setHover(Math.round(ratio * (points.length - 1)));
  };

  const innerH = H - PAD_T - PAD_B;

  // Y 轴刻度（0, mid, max）
  const yTicks = [0, max / 2, max];

  return (
    <div
      className="area-bar-chart timeseries-chart"
      ref={wrapRef}
      onMouseMove={onMove}
      onMouseLeave={() => setHover(null)}
      data-testid="timeseries-chart"
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <strong style={{ fontSize: 13 }}>{title}</strong>
        <span style={{ fontSize: 11, color: 'var(--text-3)' }}>{unit}</span>
      </div>
      {points.length === 0 ? (
        <div style={{ height: H, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-3)' }}>
          暂无数据
        </div>
      ) : (
        <>
          <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="area-bar-svg" aria-hidden="true">
            <defs>
              <linearGradient id="tsFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="rgba(212,165,116,.40)" />
                <stop offset="60%" stopColor="rgba(212,165,116,.10)" />
                <stop offset="100%" stopColor="transparent" />
              </linearGradient>
              <linearGradient id="tsStroke" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0%" stopColor="#D4A574" />
                <stop offset="100%" stopColor="#8B5CF6" />
              </linearGradient>
            </defs>
            {/* 网格 + Y 轴标签 */}
            {yTicks.map((v, i) => {
              const y = H - PAD_B - (v / max) * innerH;
              return (
                <g key={i}>
                  <line
                    x1={PAD_L} x2={W - PAD_R}
                    y1={y} y2={y}
                    stroke="var(--border-thin)"
                    strokeDasharray="3 5"
                  />
                  <text x={PAD_L - 6} y={y + 3} fontSize="10" textAnchor="end" fill="var(--text-3)">
                    {fmt(v)}
                  </text>
                </g>
              );
            })}
            <path d={areaPath} fill="url(#tsFill)" className="area-in" transform={`translate(0,0)`} />
            <path d={linePath} fill="none" stroke="url(#tsStroke)" strokeWidth="2" strokeLinecap="round" className="line-in" />
            {coords.length > 0 && (
              <circle
                cx={coords[coords.length - 1][0]}
                cy={coords[coords.length - 1][1]}
                r="3"
                fill="#D4A574"
                className="live-dot"
              />
            )}
          </svg>
          {/* X 轴标签 */}
          <div className="area-bar-labels">
            {points.map((p, i) => (
              <span key={i}>{i % labelStride === 0 ? p.t : ''}</span>
            ))}
          </div>
          {/* 十字准线 + 气泡 */}
          {hover != null && coords[hover] && (
            <div className="chart-crosshair" style={{ left: `${(coords[hover][0] / W) * 100}%` }}>
              <div className="crosshair-line" />
              <div className="crosshair-bubble" style={{ left: hover / points.length > 0.75 ? 'auto' : undefined, right: hover / points.length > 0.75 ? 'calc(100% + 10px)' : undefined }}>
                <span className="mono">{points[hover].t}</span>
                <strong className="mono">{fmt(points[hover].n)}</strong>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}