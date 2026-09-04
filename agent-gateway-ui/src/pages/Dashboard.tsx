import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Row, Col, Button, Tag, Tooltip } from 'antd';
import {
  DownloadOutlined,
  ReloadOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { HeroBanner } from '../components/framework/HeroBanner';
import { StatCard, MicronIcon } from '../components/framework/StatCard';
import { formatNum } from '../lib/format';
import { exportCsv } from '../lib/export';
import { loadDashboardReport } from '../lib/api/usage';
import type { UsageReport, UsagePoint, TopRow } from '../lib/api/usage';
import { listAuditLogs } from '../lib/api/audit';
import { getReady } from '../lib/api/health';
import { getPromptCacheRate } from '../lib/api/promptCache';
import type { PromptCacheRate } from '../lib/api/promptCache';
import type { ReadyReport } from '../lib/api/health';
import { ApiError } from '../lib/request';
import { EmptyState, ErrorState } from '../components/framework/EmptyState';
import { SkeletonPage } from '../components/framework/Skeleton';
import { AreaBarChart } from '../components/charts/AreaBarChart';
import { TrendPanel } from '../components/charts/TrendPanel';
import { useEvent } from '../hooks/useEventBus';

interface ActivityRow {
  time: string;
  actor: string;
  text: string;
  result: 'success' | 'fail' | 'deny';
  /** 下钻目标：认证/限流类 → Audit（带 keyword）；Agent 调用类 → Traces */
  link?: string;
}

/** 活动流下钻分类：认证/密钥/限流/RBAC 拒绝 → Audit；chat/agent 调用 → Traces */
function activityDrillLink(a: {
  action?: string;
  type?: string;
  resource?: string;
  actor?: string;
  result?: string;
}): string {
  const action = (a.action ?? '').toLowerCase();
  const type = (a.type ?? '').toLowerCase();
  const isAuthOrRate =
    /auth|login|token|api-?key|rate.?limit|quota|rbac|permission|secret/.test(action + ' ' + type) ||
    a.result === 'deny';
  if (isAuthOrRate) {
    const kw = a.resource || a.actor || '';
    return `/audit?keyword=${encodeURIComponent(kw)}`;
  }
  if (/chat|invoke|agent|completion|llm/.test(action + ' ' + type)) {
    return '/traces';
  }
  return '';
}

export function Dashboard() {
  const [report, setReport] = useState<UsageReport | null>(null);
  const [activities, setActivities] = useState<ActivityRow[]>([]);
  const [healthStatus, setHealthStatus] = useState<'up' | 'slow' | 'down' | 'unknown'>('unknown');
  const [health, setHealth] = useState<Record<string, 'success' | 'warning' | 'error'>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [lastRefresh, setLastRefresh] = useState<Date>(new Date());
  /** 提示缓存命中率（/actuator/metrics prompt_cache_hit/miss_total）；拉不到为 null → 显示 — */
  const [cacheRate, setCacheRate] = useState<PromptCacheRate | null>(null);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [r, audit, h, pc] = await Promise.allSettled([
        loadDashboardReport(),
        listAuditLogs({ tenant: 'primary', limit: 8 }),
        getReady().catch((e) => {
          // NOT_READY 返回 503 + 报告体，Dashboard 仍要渲染检查项
          if (e instanceof ApiError && e.payload && typeof e.payload === 'object') return e.payload as ReadyReport;
          throw e;
        }),
        getPromptCacheRate(),
      ]);
      if (r.status === 'fulfilled') setReport(r.value);
      else setError(r.reason?.message ?? '数据加载失败');

      if (audit.status === 'fulfilled') {
        setActivities(
          audit.value.slice(0, 8).map((l) => ({
            time: l.time?.slice(11, 16) ?? '--:--',
            actor: l.actor,
            text: `${l.action} ${l.resource}`,
            result: (l.result === 'fail' || l.result === 'deny' ? 'fail' : 'success') as ActivityRow['result'],
            link: activityDrillLink(l),
          })),
        );
      }
      if (h.status === 'fulfilled') {
        const comps = h.value.checks ?? {};
        const map: Record<string, 'success' | 'warning' | 'error'> = {};
        let allUp = true;
        let hasWarn = false;
        let hasError = false;
        for (const [name, c] of Object.entries(comps)) {
          const cs = typeof c === 'string' ? c : (c as { status?: string }).status;
          let s: 'success' | 'warning' | 'error';
          if (cs === 'UP' || cs === 'success') s = 'success';
          else if (cs === 'WARN' || cs === 'WARNING' || cs === 'DEGRADED') s = 'warning';
          else s = 'error';
          map[name] = s;
          if (s === 'error') { allUp = false; hasError = true; }
          else if (s === 'warning') hasWarn = true;
        }

        // 派生：cache 命中率 / latency 等指标无原生 warning 时按阈值派生
        for (const [name, c] of Object.entries(comps)) {
          if (map[name] !== 'success') continue;
          const derived = deriveHealthFromDetails(name, c, pc.status === 'fulfilled' ? pc.value : null);
          if (derived === 'warning') hasWarn = true;
          else if (derived === 'error') { hasError = true; allUp = false; }
          if (derived) map[name] = derived;
        }

        if (allUp && !hasWarn && !hasError && Object.keys(comps).length > 0) setHealthStatus('up');
        else if (hasError) setHealthStatus('down');
        else if (hasWarn) setHealthStatus('slow');
        else setHealthStatus('unknown');
        setHealth(map);
      }
      if (pc.status === 'fulfilled') setCacheRate(pc.value);
      setLastRefresh(new Date());
    } catch (e: any) {
      setError(e?.message ?? '数据加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // 30s 自动刷新
    const t = setInterval(load, 30_000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onExport = () => {
    if (!report) return;
    exportCsv('dashboard-overview', ['metric', 'value'], [
      { metric: 'requests_24h', value: report.overview.requests24h },
      { metric: 'active_keys', value: report.overview.activeKeys },
      { metric: 'error_rate', value: report.overview.errorRate.toFixed(4) },
      { metric: 'p95_latency_ms', value: report.overview.p95LatencyMs },
      { metric: 'generated_at', value: new Date().toISOString() },
    ]);
    if (report.usage24h.length > 0) {
      exportCsv(
        'dashboard-usage-24h',
        ['hour', 'requests', 'errors'],
        report.usage24h.map((p) => ({ hour: p.t, requests: p.n, errors: p.err ?? 0 })),
      );
    }
    if (report.topModels.length > 0) {
      exportCsv(
        'dashboard-top-models',
        ['model_id', 'model_name', 'requests', 'errors'],
        report.topModels.map((m) => ({
          model_id: m.id,
          model_name: m.name,
          requests: m.n,
          errors: m.err ?? 0,
        })),
      );
    }
  };

  // spark 归一化（0-1）：usage 序列给调用/错误两张卡
  const usageSpark = useMemo(() => {
    if (!report?.usage24h?.length) return [];
    const max = Math.max(...report.usage24h.map((p) => p.n), 1);
    return report.usage24h.map((p) => p.n / max);
  }, [report?.usage24h]);
  const errSpark = useMemo(() => {
    if (!report?.usage24h?.length) return [];
    const max = Math.max(...report.usage24h.map((p) => p.err ?? 0), 1);
    return report.usage24h.map((p) => (p.err ?? 0) / max);
  }, [report?.usage24h]);

  return (
    <>
      <HeroBanner
        title="系统运行态势"
        live={!!report?.live}
        sub={
          report ? (
            <>
              <span>最后刷新 {lastRefresh.toLocaleTimeString('zh-CN')} · 自动刷新 30s</span>
              {report.live ? (
                <Tag>LIVE · 实时链路</Tag>
              ) : (
                <Tooltip title="metrics 接口未接线，按审计 + 健康聚合（数据真实但精度较低）">
                  <Tag>DERIVED · 派生聚合</Tag>
                </Tooltip>
              )}
            </>
          ) : (
            <span>正在连接网关…</span>
          )
        }
        status={
          <>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button icon={<DownloadOutlined />} onClick={onExport} disabled={!report}>
              导出报表
            </Button>
          </>
        }
      />

      {loading && !report && <SkeletonPage hasCards hasTable />}

      {error && !report && (
        <ErrorState error={error} onRetry={load} retryLabel="重新加载" />
      )}

      {report && (
        <>
          {/* 趋势区(spec 2026-08-19 §6.2):7 天指标折线 + 错误链路入口;无 PG 时自动隐藏 */}
          <TrendPanel />
          <Row gutter={[16, 14]} style={{ marginBottom: 14 }}>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="amber"
                delay={0}
                label={<><MicronIcon kind="pulse" /> 24h 调用量</>}
                value={formatNum(report.overview.requests24h)}
                trend={report.live ? { direction: 'up', text: '● 实时' } : { direction: 'flat', text: '◐ 派生' }}
                spark={usageSpark}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="blue"
                delay={70}
                label={<><MicronIcon kind="key" /> 活跃 Key</>}
                value={report.overview.activeKeys}
                trend={{ direction: 'flat', text: '◐ 当前数' }}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="red"
                delay={140}
                label={<><MicronIcon kind="alert" /> 错误率</>}
                value={`${(report.overview.errorRate * 100).toFixed(2)}%`}
                trend={
                  report.overview.errorRate > 0.05
                    ? { direction: 'up', text: '⚠ 高' }
                    : { direction: 'down', text: '✓ 健康' }
                }
                spark={errSpark}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="green"
                delay={210}
                label={<><MicronIcon kind="speed" /> p95 延迟</>}
                value={report.overview.p95LatencyMs ? `${report.overview.p95LatencyMs}ms` : '—'}
                trend={{ direction: 'flat', text: '◐ 24h' }}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="green"
                delay={280}
                label={<>⚡ 提示缓存命中率</>}
                value={
                  cacheRate?.rate != null
                    ? `${(cacheRate.rate * 100).toFixed(1)}%`
                    : '—'
                }
                trend={
                  cacheRate?.rate != null
                    ? { direction: cacheRate.rate >= 0.5 ? 'up' : 'down', text: `命中 ${cacheRate.hit} / 未中 ${cacheRate.miss}` }
                    : { direction: 'flat', text: '指标未暴露' }
                }
              />
            </Col>
          </Row>

          <Row gutter={[16, 14]} style={{ marginBottom: 14 }} align="stretch">
            <Col xs={24} lg={16} className="dash-col">
              <div className="content-card dash-fill">
                <div className="content-card-head">
                  <div className="content-card-title">近 24h 调用量</div>
                  <div className="chart-legend">
                    <span className="chart-legend-item">
                      <i className="chart-legend-dot" style={{ background: '#38BDF8' }} />
                      调用 {report.usage24h.reduce((acc, p) => acc + p.n, 0)}
                    </span>
                    <span className="chart-legend-item">
                      <i className="chart-legend-dot" style={{ background: 'var(--ant-warning)' }} />
                      错误 {report.usage24h.reduce((acc, p) => acc + (p.err ?? 0), 0)}
                    </span>
                  </div>
                </div>
                <UsageBarChart points={report.usage24h} />
              </div>
            </Col>

            <Col xs={24} lg={8} className="dash-col">
              <div className="content-card dash-fill">
                <div className="content-card-head">
                  <div className="content-card-title">最近事件</div>
                  <a style={{ color: 'var(--ant-primary)', fontSize: 13 }} href="/audit">
                    全部 →
                  </a>
                </div>
                {activities.length === 0 ? (
                  <EmptyState variant="no-data" description="暂无事件" />
                ) : (
                  <div className="activity-list">
                    {activities.map((a, i) => (
                      <div className="activity-row" key={i}>
                        <span
                          className={`status-dot ${a.result === 'success' ? 'success' : 'error'}`}
                          style={{ width: 6, height: 6, marginTop: 6, flexShrink: 0 }}
                        />
                        <span className="activity-time">{a.time}</span>
                        <span className="activity-text">
                          {a.link ? (
                            <Link
                              to={a.link}
                              data-testid="activity-drill-link"
                              style={{ color: 'inherit' }}
                            >
                              <strong>{a.actor}</strong> {a.text}
                            </Link>
                          ) : (
                            <>
                              <strong>{a.actor}</strong> {a.text}
                            </>
                          )}
                          {a.result !== 'success' && (
                            <Tag color="error" style={{ marginLeft: 6, fontSize: 10 }}>
                              failed
                            </Tag>
                          )}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </Col>
          </Row>

          <Row gutter={[16, 16]} align="stretch">
            <Col xs={24} md={8} className="dash-col">
              <div className="content-card dash-fill dash-card-health">
                <div className="content-card-head">
                  <div className="content-card-title">组件健康</div>
                  <span
                    className={`health-pill health-pill--${healthStatus}`}
                    title={`整体状态 ${healthStatus}`}
                  >
                    <span className="health-pill-dot" />
                    {HEALTH_LABEL[healthStatus]}
                  </span>
                  {Object.values(health).some((s) => s === 'error' || s === 'warning') && (
                    <Link
                      to="/health"
                      data-testid="health-fix-link"
                      style={{ color: 'var(--ant-primary)', fontSize: 13 }}
                    >
                      去处理 →
                    </Link>
                  )}
                </div>
                {Object.keys(health).length === 0 ? (
                  <EmptyState variant="no-data" description="暂无健康数据" />
                ) : (
                  Object.entries(health).map(([name, s]) => (
                    <SystemStatusRow key={name} label={name} status={s} />
                  ))
                )}
              </div>
            </Col>

            <Col xs={24} md={8} className="dash-col">
              <div className="content-card dash-fill">
                <div className="content-card-head">
                  <div className="content-card-title">Top 模型</div>
                  <a style={{ color: 'var(--ant-primary)', fontSize: 13 }} href="/models">
                    管理 →
                  </a>
                </div>
                {report.topModels.length === 0 ? (
                  <EmptyState variant="no-data" description="暂无模型调用数据" />
                ) : (
                  (() => {
                    const total = report.topModels.reduce((acc, x) => acc + x.n, 0);
                    return report.topModels.map((m, i) => (
                      <TopRow
                        key={m.id}
                        rank={i + 1}
                        name={m.name}
                        n={m.n}
                        err={m.err}
                        pct={total > 0 ? (m.n / total) * 100 : 0}
                        last={i === report.topModels.length - 1}
                      />
                    ));
                  })()
                )}
              </div>
            </Col>

            <Col xs={24} md={8} className="dash-col">
              <div className="content-card dash-fill">
                <div className="content-card-head">
                  <div className="content-card-title">租户分布</div>
                  <a style={{ color: 'var(--ant-primary)', fontSize: 13 }} href="/rbac">
                    管理 →
                  </a>
                </div>
                {report.topTenants.length === 0 ? (
                  <EmptyState variant="no-data" description="暂无租户数据" />
                ) : (
                  report.topTenants.map((t, i) => (
                    <TenantBar
                      key={t.id}
                      name={t.name}
                      n={t.n}
                      pct={
                        (t.n /
                          Math.max(1, report.topTenants.reduce((acc, x) => acc + x.n, 0))) *
                        100
                      }
                      color={i === 0 ? 'var(--brand-amber)' : 'var(--ant-primary)'}
                      last={i === report.topTenants.length - 1}
                    />
                  ))
                )}
              </div>
            </Col>
          </Row>
        </>
      )}
    </>
  );
}

function UsageBarChart({ points }: { points: UsagePoint[] }) {
  if (points.length === 0) {
    return <EmptyState variant="no-data" description="暂无 24h 数据" />;
  }
  return <AreaBarChart points={points} />;
}

function SystemStatusRow({
  label,
  status,
}: {
  label: string;
  status: 'success' | 'warning' | 'error';
}) {
  const Icon = status === 'success' ? CheckCircleOutlined : status === 'warning' ? WarningOutlined : WarningOutlined;
  const color =
    status === 'success' ? 'var(--ant-success)' : status === 'warning' ? 'var(--ant-warning)' : 'var(--ant-error)';
  return (
    <div className="health-row">
      <Icon style={{ color, fontSize: 12 }} />
      <span>{label}</span>
      <span style={{ color, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
        {status === 'success' ? 'UP' : status === 'warning' ? 'WARN' : 'DOWN'}
      </span>
    </div>
  );
}

function TopRow({
  rank,
  name,
  n,
  err,
  pct,
  last,
}: {
  rank: number;
  name: string;
  n: number;
  err?: number;
  pct?: number;
  last?: boolean;
}) {
  return (
    <div
      style={{
        padding: '7px 0',
        borderBottom: last ? 'none' : '1px dashed var(--border-thin)',
        position: 'relative',
      }}
    >
      {/* 底层占比条：随排名渐淡 */}
      {pct != null && pct > 0 && (
        <div
          aria-hidden="true"
          style={{
            position: 'absolute',
            left: 0,
            top: 4,
            bottom: 4,
            width: `${Math.max(3, pct)}%`,
            background: 'linear-gradient(90deg, var(--brand-amber-soft) 0%, transparent 100%)',
            borderRadius: 3,
            pointerEvents: 'none',
          }}
        />
      )}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', position: 'relative' }}>
        <span style={{ color: 'var(--text-2)', display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span
            className="mono"
            style={{
              background: rank <= 3 ? 'var(--brand-amber-soft)' : 'var(--bg-sunken)',
              color: rank <= 3 ? 'var(--brand-amber)' : 'var(--text-3)',
              padding: '0 6px',
              borderRadius: 3,
              fontSize: 10,
              fontWeight: rank <= 3 ? 600 : 400,
              border: rank <= 3 ? '1px solid rgba(212,165,116,.3)' : 'none',
              flexShrink: 0,
            }}
          >
            #{rank}
          </span>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}</span>
        </span>
        <span style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
          {err ? <Tag color="error" style={{ margin: 0, fontSize: 10 }}>{err}</Tag> : null}
          <span className="mono" style={{ color: 'var(--text-1)' }}>
            {formatNum(n)}
          </span>
          {pct != null && (
            <span className="mono" style={{ color: 'var(--text-3)', fontSize: 10, minWidth: 34, textAlign: 'right' }}>
              {pct.toFixed(0)}%
            </span>
          )}
        </span>
      </div>
    </div>
  );
}

function TenantBar({
  name,
  n,
  pct,
  color,
  last,
}: {
  name: string;
  n: number;
  pct: number;
  color: string;
  last?: boolean;
}) {
  return (
    <div className="tenant-row">
      <div className="tenant-row-head">
        <span className="tenant-row-name">{name}</span>
        <span className="tenant-row-val mono">
          {formatNum(n)} <em>{pct.toFixed(1)}%</em>
        </span>
      </div>
      <div className="tenant-row-track">
        <div
          style={{
            width: `${Math.max(2, pct)}%`,
            background: `linear-gradient(90deg, ${color} 0%, ${color}CC 100%)`,
            boxShadow: `0 0 8px ${color}66`,
          }}
          className="tenant-row-fill"
        />
      </div>
    </div>
  );
}

void ClockCircleOutlined; // reserved for future time-range filter

const HEALTH_LABEL: Record<string, string> = {
  up: '全部正常',
  slow: '部分降级',
  down: '存在故障',
  unknown: '未知',
};

/**
 * 派生健康状态：当组件本身报 UP（无原生 warning 通道）时，
 * 按其内部 details（latencyMs / p99 / dbLatencyMs / cacheRate 等）
 * 阈值把状态降级为 warning / error。
 *
 * 返回 undefined 表示无依据，保持原状态。
 */
function deriveHealthFromDetails(
  _name: string,
  c: unknown,
  cacheRate: PromptCacheRate | null,
): 'warning' | 'error' | undefined {
  // Cache 命中率：>60% ok / 30~60% warning / <30% error
  if (cacheRate && typeof cacheRate.rate === 'number') {
    if (cacheRate.rate < 0.3) return 'error';
    if (cacheRate.rate < 0.6) return 'warning';
  }
  // 尝试从 details 对象里取延迟字段
  if (c && typeof c === 'object') {
    const d = c as { latencyMs?: number; p99Ms?: number; dbLatencyMs?: number; responseTimeMs?: number };
    const anyLatency = d.latencyMs ?? d.p99Ms ?? d.dbLatencyMs ?? d.responseTimeMs;
    if (typeof anyLatency === 'number') {
      if (anyLatency > 500) return 'error';
      if (anyLatency > 100) return 'warning';
    }
  }
  return undefined;
}