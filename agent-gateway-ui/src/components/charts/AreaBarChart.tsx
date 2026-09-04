/**
 * AreaBarChart — 渐变面积 + 柱混合图（24h 时序）
 *
 * 面积层：SVG 平滑曲线（Catmull-Rom → bezier）+ 垂直渐变填充，
 *         描摹趋势走向，是"AI 产品图表"的语言核心。
 * 柱层：  保留 hover 交互锚点（Tooltip + 悬停发光），高度与面积对齐。
 * 错误线：err>0 的桶叠加警示色细柱。
 */
import { Tooltip } from 'antd';
import { useMemo, useRef, useState } from 'react';

export interface ChartPoint {
  t: string;
  n: number;
  err?: number;
}

const W = 720;
const H = 230;
const PAD_B = 16;

/** Catmull-Rom 平滑插值 → SVG path（闭合到基线用于面积） */
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

export function AreaBarChart({ points }: { points: ChartPoint[] }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);

  const { coords, linePath, areaPath, max } = useMemo(() => {
    const max = Math.max(1, ...points.map((p) => p.n));
    const step = points.length > 1 ? W / (points.length - 1) : W;
    const coords: [number, number][] = points.map((p, i) => [
      +(i * step).toFixed(1),
      +(H - PAD_B - (p.n / max) * (H - PAD_B - 8)).toFixed(1),
    ]);
    return {
      coords,
      linePath: smoothPath(coords, false),
      areaPath: smoothPath(coords, true),
      max,
    };
  }, [points]);

  /** 鼠标移动 → 最近桶索引（十字准线跟随） */
  const onMove = (e: React.MouseEvent) => {
    const el = wrapRef.current;
    if (!el || points.length < 2) return;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    setHover(Math.round(ratio * (points.length - 1)));
  };

  if (points.length === 0) return null;

  const hp = hover != null ? points[hover] : null;
  const hc = hover != null ? coords[hover] : null;
  const hoverRatio = hover != null && points.length > 1 ? hover / (points.length - 1) : 0;

  return (
    <div className="area-bar-chart" ref={wrapRef} onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="area-bar-svg" aria-hidden="true">
        <defs>
          <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgba(56, 189, 248, .34)" />
            <stop offset="55%" stopColor="rgba(56, 189, 248, .10)" />
            <stop offset="100%" stopColor="transparent" />
          </linearGradient>
          <linearGradient id="lineStroke" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#38BDF8" />
            <stop offset="100%" stopColor="#8B5CF6" />
          </linearGradient>
        </defs>
        {/* 横网格 */}
        {[0.25, 0.5, 0.75].map((f) => (
          <line
            key={f}
            x1="0" x2={W}
            y1={(H - PAD_B) * f} y2={(H - PAD_B) * f}
            stroke="var(--border-thin)"
            strokeDasharray="3 5"
          />
        ))}
        <path d={areaPath} fill="url(#areaFill)" className="area-in" />
        <path
          d={linePath}
          fill="none"
          stroke="url(#lineStroke)"
          strokeWidth="2"
          strokeLinecap="round"
          className="line-in"
        />
        {coords.length > 0 && (
          <circle cx={coords[coords.length - 1][0]} cy={coords[coords.length - 1][1]} r="3" fill="#38BDF8" className="live-dot" />
        )}
      </svg>
      {/* 柱层：hover 锚点 */}
      <div className="area-bar-bars">
        {points.map((p, i) => (
          <Tooltip key={i} title={`${p.t} · ${p.n} 次${p.err ? ` · 错 ${p.err}` : ''}`}>
            <div
              className={`abar${p.err && p.err > 0 ? ' has-err' : ''}${hover === i ? ' hover' : ''}`}
              style={{
                height: `${Math.max(2, (p.n / max) * (H - PAD_B - 8))}px`,
                animationDelay: `${Math.min(i * 16, 360)}ms`,
              }}
            />
          </Tooltip>
        ))}
      </div>
      {/* 十字准线 + 悬浮数值气泡 */}
      {hc && hp && (
        <div className="chart-crosshair" style={{ left: `${hoverRatio * 100}%` }}>
          <div className="crosshair-line" />
          <div className="crosshair-dot" style={{ bottom: hc[1] + PAD_B + 10 }} />
          <div className="crosshair-bubble" style={{ left: hoverRatio > 0.75 ? 'auto' : undefined, right: hoverRatio > 0.75 ? 'calc(100% + 10px)' : undefined }}>
            <span className="mono">{hp.t}</span>
            <strong className="mono">{hp.n}</strong>
            <span>次</span>
            {hp.err ? <em className="mono">错 {hp.err}</em> : null}
          </div>
        </div>
      )}
      {/* 时刻标签 */}
      <div className="area-bar-labels">
        {points.map((p, i) => (
          <span key={i}>{i % 4 === 0 ? p.t : ''}</span>
        ))}
      </div>
    </div>
  );
}
