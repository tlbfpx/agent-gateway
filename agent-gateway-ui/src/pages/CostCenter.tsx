/**
 * CostCenter — 成本中心（阶段2.1 最高 ROI 新菜单）
 *
 * 设计目标：
 *   - 让运营一眼看到：今日/本周/本月谁花了多少钱、调用了多少次、错误率多少
 *   - 4 维度切片：租户 / API Key / 模型 / 日期
 *   - 一键导出账单（CSV）→ 接财务系统
 *   - URL 参数同步（?range=24h&dim=tenant&persist=true）—— 与 Round3 Budgets/AlertCenter 同款
 *
 * 数据：
 *   - 优先走后端 /admin/metrics/cost（live=true）
 *   - 降级：从 audit 聚合 + 价格表估算（PRICE_TABLE in usage.ts）
 */
import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Row,
  Col,
  Tabs,
  Button,
  Select,
  Table,
  Tag,
  Space,
  Tooltip,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ReloadOutlined,
  DownloadOutlined,
  DollarOutlined,
  ThunderboltOutlined,
  ClockCircleOutlined,
  WarningOutlined,
  BellOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { StatCard, MicronIcon } from '../components/framework/StatCard';
import {
  loadCostReport,
  type CostReport,
  type CostByDim,
} from '../lib/api/usage';
import type { ReportRange } from '../lib/api/usage';
import { formatNum } from '../lib/format';
import { exportCsv } from '../lib/export';
import { ErrorState, EmptyState } from '../components/framework/EmptyState';
import { ScheduledReportDialog } from '../components/billing/ScheduledReportDialog';
import { TimeseriesChart } from '../components/charts/TimeseriesChart';
import { ModelSharePie } from '../components/charts/ModelSharePie';
import { PeriodCompareBar } from '../components/charts/PeriodCompareBar';
import { deriveTimeseries, deriveBreakdown, deriveCompare } from '../lib/api/cost';

type Dim = 'tenant' | 'key' | 'model' | 'day';
type Range = ReportRange;

const VALID_RANGES: Range[] = ['24h', '7d', '30d', '90d'];
const VALID_DIMS: Dim[] = ['tenant', 'key', 'model', 'day'];

function parseRangeParam(v: string | null): Range {
  return v && (VALID_RANGES as string[]).includes(v) ? (v as Range) : '24h';
}
function parseDimParam(v: string | null): Dim {
  return v && (VALID_DIMS as string[]).includes(v) ? (v as Dim) : 'tenant';
}

export function CostCenter() {
  // Round4 评审项：range / dim 与 URL 双向绑定，便于分享/收藏/刷新恢复。
  // 与 Budgets.tsx、AlertCenter.tsx 同款模式；persist=true 时写入本地偏好。
  const [searchParams, setSearchParams] = useSearchParams();
  const range = parseRangeParam(searchParams.get('range'));
  const dim = parseDimParam(searchParams.get('dim'));
  const persist = searchParams.get('persist') === 'true';

  const setRange = useCallback(
    (v: Range) => {
      const next = new URLSearchParams(searchParams);
      if (v === '24h') next.delete('range');
      else next.set('range', v);
      // 仅保留有意义的参数，空查询串不污染 URL
      const qs = next.toString();
      setSearchParams(qs ? `?${qs}` : '', { replace: true });
      if (persist) {
        try { localStorage.setItem('agent-gateway.cost.range', v); } catch { /* ignore */ }
      }
    },
    [searchParams, setSearchParams, persist],
  );

  const setDim = useCallback(
    (v: Dim) => {
      const next = new URLSearchParams(searchParams);
      if (v === 'tenant') next.delete('dim');
      else next.set('dim', v);
      const qs = next.toString();
      setSearchParams(qs ? `?${qs}` : '', { replace: true });
      if (persist) {
        try { localStorage.setItem('agent-gateway.cost.dim', v); } catch { /* ignore */ }
      }
    },
    [searchParams, setSearchParams, persist],
  );

  // persist=true 时首次进入从 localStorage 还原默认 range/dim
  useEffect(() => {
    if (!persist) return;
    try {
      const savedRange = localStorage.getItem('agent-gateway.cost.range') as Range | null;
      const savedDim = localStorage.getItem('agent-gateway.cost.dim') as Dim | null;
      if (savedRange && savedRange !== range && VALID_RANGES.includes(savedRange)) {
        setRange(savedRange);
      }
      if (savedDim && savedDim !== dim && VALID_DIMS.includes(savedDim)) {
        setDim(savedDim);
      }
    } catch { /* ignore */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [report, setReport] = useState<CostReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [subDialogOpen, setSubDialogOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setReport(await loadCostReport(range));
    } catch (e: any) {
      setError(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => {
    void load();
  }, [load]);

  const onExport = () => {
    if (!report) return;
    exportCsv(
      `cost-by-${dim}-${range}`,
      ['dim', 'id', 'name', 'calls', 'tokens', 'errors', 'avg_latency_ms', 'cost_cny'],
      getDimRows(report, dim).map((r) => ({
        dim: r.dim,
        id: r.id,
        name: r.name,
        calls: r.calls,
        tokens: r.tokens,
        errors: r.errors,
        avg_latency_ms: r.avgLatencyMs,
        cost_cny: r.costCny.toFixed(4),
      })),
    );
  };

  const rows = report ? getDimRows(report, dim) : [];
  const timeseries = deriveTimeseries(report);
  const breakdown = deriveBreakdown(report, 'model');
  const compare = deriveCompare(report);

  return (
    <>
      <PageHeader
        eyebrow="Cost · 成本"
        title="成本中心"
        sub={
          report ? (
            <span>
              {range === '24h' ? '近 24 小时' : range === '7d' ? '近 7 天' : range === '30d' ? '近 30 天' : '近 90 天'} ·{' '}
              {report.live ? (
                <Tag color="success">● 实时</Tag>
              ) : (
                <Tooltip title="价格估算口径：input 60% / output 40%（演示用，与计费系统对齐需后端落地）">
                  <Tag color="warning">◐ 派生</Tag>
                </Tooltip>
              )}
            </span>
          ) : (
            '加载中…'
          )
        }
        actions={
          <Space>
            <Select
              value={range}
              onChange={(v) => setRange(v as Range)}
              style={{ width: 120 }}
              data-testid="cost-range-select"
              options={[
                { value: '24h', label: '近 24h' },
                { value: '7d', label: '近 7 天' },
                { value: '30d', label: '近 30 天' },
                { value: '90d', label: '近 90 天' },
              ]}
            />
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button
              icon={<BellOutlined />}
              onClick={() => setSubDialogOpen(true)}
              data-testid="cost-subscribe-btn"
            >
              订阅
            </Button>
            <Button icon={<DownloadOutlined />} onClick={onExport} disabled={!report}>
              导出账单
            </Button>
          </Space>
        }
      />

      {error && !report && <ErrorState error={error} onRetry={load} />}

      {report && (
        <>
          {/* 总览四联 */}
          <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="amber"
                label={<><MicronIcon kind="coins" /> 总成本</>}
                value={`¥${report.total.costCny.toFixed(2)}`}
                trend={{ direction: 'flat', text: `¥${(report.total.costCny / Math.max(1, report.total.calls) * 1000).toFixed(2)}/k 次` }}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="blue"
                label={<><MicronIcon kind="bolt" /> 调用次数</>}
                value={formatNum(report.total.calls)}
                trend={{ direction: 'flat', text: `${formatNum(report.total.tokens)} tokens` }}
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="red"
                label={<><MicronIcon kind="alert" /> 失败率</>}
                value={
                  report.total.calls > 0
                    ? `${((report.total.errors / report.total.calls) * 100).toFixed(2)}%`
                    : '—'
                }
                trend={
                  report.total.errors / Math.max(1, report.total.calls) > 0.05
                    ? { direction: 'up', text: '⚠ 高' }
                    : { direction: 'down', text: '✓ 正常' }
                }
              />
            </Col>
            <Col xs={24} sm={12} md={6}>
              <StatCard
                accent="green"
                label={<><MicronIcon kind="speed" /> 平均延迟</>}
                value={report.total.avgLatencyMs ? `${report.total.avgLatencyMs}ms` : '—'}
                trend={{ direction: 'flat', text: '◐ 平均' }}
              />
            </Col>
          </Row>

          {/* 三联图（Round 10 B-10） */}
          <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
            <Col xs={24} lg={16}>
              <TimeseriesChart
                data={timeseries}
                range={range}
                metric="cost"
                empty={!report}
              />
            </Col>
            <Col xs={24} lg={8}>
              <ModelSharePie
                slices={breakdown.map((b) => ({ label: b.name, value: b.costCny }))}
                total={breakdown.reduce((acc, b) => acc + b.costCny, 0)}
                empty={!report}
              />
            </Col>
          </Row>
          <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
            <Col span={24}>
              <PeriodCompareBar
                currentLabel={compare.currentLabel}
                previousLabel={compare.previousLabel}
                currentValue={compare.currentValue}
                previousValue={compare.previousValue}
                deltaPct={compare.deltaPct}
              />
            </Col>
          </Row>

          {/* 维度切片 */}
          <Tabs
            activeKey={dim}
            onChange={(k) => setDim(k as Dim)}
            style={{ marginBottom: 16 }}
            items={[
              { key: 'tenant', label: `按租户 · ${report.byTenant.length}` },
              { key: 'key', label: `按 API Key · ${report.byKey.length}` },
              { key: 'model', label: `按模型 · ${report.byModel.length}` },
              { key: 'day', label: `按日期 · ${report.byDay.length}` },
            ]}
          />

          <CostTable rows={rows} loading={loading} />

          {/* 暗色提示卡：价格估算说明 */}
          {!report.live && (
            <div
              style={{
                marginTop: 16,
                padding: 12,
                background: 'var(--bg-sunken)',
                border: '1px dashed var(--border-thin)',
                borderRadius: 6,
                color: 'var(--text-3)',
                fontSize: 12,
              }}
            >
              <DollarOutlined /> 价格表仅作演示：input/output token 单价按 60/40 估算。
              <strong style={{ color: 'var(--text-1)' }}> 正式账单请对接计费系统</strong>，
              或在 gateway-bootstrap 启用 <code>/admin/metrics/cost</code> 实时接口。
            </div>
          )}
        </>
      )}

      <ScheduledReportDialog
        open={subDialogOpen}
        onClose={() => setSubDialogOpen(false)}
        currentRange={range}
        currentDim={dim}
      />
    </>
  );
}

function getDimRows(r: CostReport, dim: Dim): CostByDim[] {
  switch (dim) {
    case 'tenant':
      return r.byTenant;
    case 'key':
      return r.byKey;
    case 'model':
      return r.byModel;
    case 'day':
      return r.byDay;
  }
}

function CostTable({ rows, loading }: { rows: CostByDim[]; loading: boolean }) {
  if (rows.length === 0) {
    return <EmptyState variant="no-data" description="当前维度暂无数据 · 发生调用后自动生成账单" />;
  }
  const totalCost = rows.reduce((acc, r) => acc + r.costCny, 0);

  const cols: TableColumnsType<CostByDim> = [
    {
      title: '对象',
      dataIndex: 'name',
      render: (v: string, r) => (
        <Space>
          <strong>{v}</strong>
          <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)' }}>
            {r.id}
          </span>
        </Space>
      ),
    },
    {
      title: '调用次数',
      dataIndex: 'calls',
      align: 'right',
      sorter: (a, b) => a.calls - b.calls,
      render: (v: number) => <span className="num">{formatNum(v)}</span>,
    },
    {
      title: 'Token 消耗',
      dataIndex: 'tokens',
      align: 'right',
      sorter: (a, b) => a.tokens - b.tokens,
      render: (v: number) => (
        <Tooltip title={`≈ ${formatNum(Math.round(v / 1000))}k tokens`}>
          <span className="num">{formatNum(v)}</span>
        </Tooltip>
      ),
    },
    {
      title: '平均延迟',
      dataIndex: 'avgLatencyMs',
      align: 'right',
      sorter: (a, b) => a.avgLatencyMs - b.avgLatencyMs,
      render: (v: number) => (
        <Space size={4}>
          <ClockCircleOutlined style={{ fontSize: 10, color: 'var(--text-3)' }} />
          <span className="num">{v}ms</span>
        </Space>
      ),
    },
    {
      title: '失败次数',
      dataIndex: 'errors',
      align: 'right',
      sorter: (a, b) => a.errors - b.errors,
      render: (v: number, r) =>
        v > 0 ? (
          <Tooltip title={`失败率 ${((v / r.calls) * 100).toFixed(2)}%`}>
            <Tag color="error" style={{ margin: 0 }}>
              <WarningOutlined /> {v}
            </Tag>
          </Tooltip>
        ) : (
          <span style={{ color: 'var(--text-3)' }}>—</span>
        ),
    },
    {
      title: '成本 (¥)',
      dataIndex: 'costCny',
      align: 'right',
      sorter: (a, b) => a.costCny - b.costCny,
      defaultSortOrder: 'descend',
      render: (v: number, r) => {
        const pct = totalCost > 0 ? (v / totalCost) * 100 : 0;
        return (
          <Tooltip title={`占比 ${pct.toFixed(1)}% · 单次 ¥${(v / r.calls).toFixed(4)}`}>
            <Space size={6}>
              <strong style={{ color: 'var(--brand-amber)' }}>¥{v.toFixed(2)}</strong>
              <span className="mono" style={{ fontSize: 11, color: 'var(--text-3)' }}>
                {pct.toFixed(0)}%
              </span>
            </Space>
          </Tooltip>
        );
      },
    },
    {
      title: '操作',
      key: 'drill',
      width: 150,
      render: (_, r) => (
        <Space size={12}>
          <Link
            to={`/budgets?tenant=${encodeURIComponent(r.id)}`}
            data-testid="cost-drill-budget"
          >
            查看预算
          </Link>
          <Link
            to={`/alerts?q=${encodeURIComponent(r.id)}`}
            data-testid="cost-drill-alert"
          >
            相关告警
          </Link>
        </Space>
      ),
    },
  ];

  return (
    <Table<CostByDim>
      rowKey="id"
      columns={cols}
      dataSource={rows}
      loading={loading}
      pagination={false}
      style={{ background: 'var(--bg-surface)', borderRadius: 'var(--r-lg)' }}
    />
  );
}

void ThunderboltOutlined;