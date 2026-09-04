/**
 * PeriodCompareBar — 同期对比柱状图（零依赖 SVG，Round 10）
 *
 * 用途：本周 vs 上周 / 本月 vs 上月 成本对比
 * - 两根并列柱 + 差值百分比徽章
 * - 当前 > 上期：红色徽章（成本上升需关注）
 * - 当前 < 上期：绿色徽章（成本下降）
 *
 * GW-UX-CEST-003
 */
import { ArrowUpOutlined, ArrowDownOutlined, MinusOutlined } from '@ant-design/icons';

export interface PeriodCompareBarProps {
  currentLabel: string;
  previousLabel: string;
  currentValue: number;
  previousValue: number;
  /** 当前值格式（默认元） */
  formatValue?: (n: number) => string;
  /** 反转"上升 = 好"的语义（如延迟：上升=坏）；默认成本语义(false) */
  invertTrend?: boolean;
}

export function PeriodCompareBar({
  currentLabel,
  previousLabel,
  currentValue,
  previousValue,
  formatValue,
  invertTrend = false,
}: PeriodCompareBarProps) {
  const fmt = formatValue ?? ((n: number) => `¥${n.toFixed(2)}`);
  const max = Math.max(currentValue, previousValue, 1);
  const delta = currentValue - previousValue;
  const deltaPct = previousValue > 0 ? (delta / previousValue) * 100 : 0;
  // invert=true 时：上升=绿色(好)、下降=红色(坏)
  const goodWhenUp = !invertTrend ? delta < 0 : delta > 0;
  const flat = Math.abs(deltaPct) < 0.5;
  const trendDir: 'up' | 'down' | 'flat' = flat ? 'flat' : delta > 0 ? 'up' : 'down';
  const trendColor = flat ? 'var(--text-3)' : goodWhenUp ? 'var(--ant-success, #52c41a)' : 'var(--ant-error, #ff4d4f)';

  const BAR_W = 56;
  const GAP = 36;
  const TOTAL_W = BAR_W * 2 + GAP;
  const H = 140;
  const BASE = H - 24;
  const SCALE = (BASE - 8) / max;
  const curH = Math.max(4, currentValue * SCALE);
  const prevH = Math.max(4, previousValue * SCALE);

  return (
    <div className="period-compare-bar" data-testid="period-compare-bar" style={{ padding: '12px 16px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <strong style={{ fontSize: 13 }}>同期对比</strong>
        <span
          className="mono"
          style={{ fontSize: 12, color: trendColor, fontWeight: 600 }}
          data-testid="period-compare-delta"
        >
          {flat ? <MinusOutlined /> : delta > 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
          {' '}
          {flat ? '持平' : `${Math.abs(deltaPct).toFixed(1)}%`}
        </span>
      </div>
      <svg width={TOTAL_W + 16} height={H} viewBox={`0 0 ${TOTAL_W + 16} ${H}`} aria-hidden="true">
        {/* 基线 */}
        <line x1="0" x2={TOTAL_W + 16} y1={BASE} y2={BASE} stroke="var(--border-thin)" strokeDasharray="3 4" />
        {/* 上期柱 */}
        <rect
          x="8"
          y={BASE - prevH}
          width={BAR_W}
          height={prevH}
          rx="3"
          fill="rgba(139, 92, 246, .55)"
          stroke="#8B5CF6"
          strokeWidth="1"
        >
          <title>{`${previousLabel}: ${fmt(previousValue)}`}</title>
        </rect>
        <text x={8 + BAR_W / 2} y={BASE + 14} textAnchor="middle" fontSize="10" fill="var(--text-3)">
          {previousLabel}
        </text>
        <text x={8 + BAR_W / 2} y={BASE - prevH - 6} textAnchor="middle" fontSize="11" fill="var(--text-3)" className="mono">
          {fmt(previousValue)}
        </text>
        {/* 本期柱 */}
        <rect
          x={8 + BAR_W + GAP}
          y={BASE - curH}
          width={BAR_W}
          height={curH}
          rx="3"
          fill="rgba(212, 165, 116, .70)"
          stroke="#D4A574"
          strokeWidth="1.5"
        >
          <title>{`${currentLabel}: ${fmt(currentValue)}`}</title>
        </rect>
        <text x={8 + BAR_W + GAP + BAR_W / 2} y={BASE + 14} textAnchor="middle" fontSize="10" fill="var(--text-2)">
          {currentLabel}
        </text>
        <text x={8 + BAR_W + GAP + BAR_W / 2} y={BASE - curH - 6} textAnchor="middle" fontSize="11" fill="var(--text-1)" className="mono" fontWeight="600">
          {fmt(currentValue)}
        </text>
      </svg>
      {/* 趋势提示 */}
      <div style={{ fontSize: 11, color: 'var(--text-3)', marginTop: 4 }}>
        {trendDir === 'flat'
          ? '成本与上期持平'
          : goodWhenUp
            ? `较上期${delta < 0 ? '下降' : '上升'} ${Math.abs(deltaPct).toFixed(1)}%（${invertTrend ? '延迟降低，性能更好' : '成本下降'}）`
            : `较上期${delta > 0 ? '上升' : '下降'} ${Math.abs(deltaPct).toFixed(1)}%（${invertTrend ? '延迟升高，需关注' : '成本上升，需关注'}）`}
      </div>
    </div>
  );
}