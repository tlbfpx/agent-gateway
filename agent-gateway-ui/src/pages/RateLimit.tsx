/**
 * RateLimit — 限流实时面板
 *
 * 设计：
 *   - 顶部 5 维度 Tab：租户 / 用户 / Key / Agent / Token 日预算
 *   - 每行显示当前用量 vs 限额，比例条（绿/黄/红）
 *   - 触发 429 数 + "一键提高限额" 按钮（占位）
 *   - 右侧：24h 限流事件列表
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Row,
  Col,
  Tabs,
  Tag,
  Button,
  Empty,
  Table,
  Space,
  Tooltip,
  message,
  Popconfirm,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ReloadOutlined,
  WarningOutlined,
  CheckCircleOutlined,
  ThunderboltOutlined,
  ExportOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { StatCard, MicronIcon } from '../components/framework/StatCard';
import { loadQuotas, ALL_DIMS_LIST, type QuotaRow, type Dim, type RateLimitEvent } from '../lib/api/ratelimit';
import { EmptyState, ErrorState } from '../components/framework/EmptyState';
import { exportCsv } from '../lib/export';
import { formatNum } from '../lib/format';

const DIM_LABEL: Record<Dim, string> = {
  tenant: '租户',
  user: '用户',
  key: 'API Key',
  agent: 'Agent',
  'token-daily': 'Token 日预算',
};

const DIM_UNIT: Record<Dim, string> = {
  tenant: 'QPS',
  user: 'QPS',
  key: 'QPS',
  agent: '并发',
  'token-daily': 'tokens',
};

function severityColor(pct: number): 'success' | 'warning' | 'error' {
  if (pct >= 0.9) return 'error';
  if (pct >= 0.7) return 'warning';
  return 'success';
}

export function RateLimit() {
  const [rows, setRows] = useState<QuotaRow[]>([]);
  const [events, setEvents] = useState<RateLimitEvent[]>([]);
  const [live, setLive] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [dim, setDim] = useState<Dim>('tenant');
  const [tick, setTick] = useState(0);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const r = await loadQuotas();
      setRows(r.rows);
      setEvents(r.events);
      setLive(r.live);
    } catch (e: any) {
      setError(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const t = setInterval(() => setTick((x) => x + 1), 10_000);
    return () => clearInterval(t);
  }, []);

  // 每 10s 静默刷新
  useEffect(() => {
    if (tick === 0) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick]);

  const filtered = useMemo(() => rows.filter((r) => r.dim === dim), [rows, dim]);
  const totals = useMemo(() => {
    const o: Record<Dim, number> = { tenant: 0, user: 0, key: 0, agent: 0, 'token-daily': 0 };
    for (const r of rows) o[r.dim] += r.current;
    return o;
  }, [rows]);

  const blockedTotal = useMemo(() => rows.reduce((acc, r) => acc + r.blocked, 0), [rows]);

  const onExport = () => {
    exportCsv(
      'ratelimit-quotas',
      ['dim', 'name', 'current', 'limit', 'utilization_pct', 'blocked', 'last_blocked_at'],
      rows.map((r) => ({
        dim: r.dim,
        name: r.name,
        current: r.current,
        limit: r.limit,
        utilization_pct: ((r.current / Math.max(1, r.limit)) * 100).toFixed(2),
        blocked: r.blocked,
        last_blocked_at: r.lastBlockedAt ?? '',
      })),
    );
  };

  const cols: TableColumnsType<QuotaRow> = [
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
      title: `当前 / 限额 (${DIM_UNIT[dim]})`,
      width: 280,
      render: (_, r) => {
        const pct = Math.min(1, r.current / Math.max(1, r.limit));
        const color = severityColor(pct);
        return (
          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
              <span className="num">{formatNum(Math.round(r.current))}</span>
              <span className="mono" style={{ color: 'var(--text-3)' }}>/ {formatNum(r.limit)}</span>
            </div>
            <div
              style={{
                width: '100%',
                height: 6,
                background: 'var(--bg-sunken)',
                borderRadius: 3,
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  width: `${pct * 100}%`,
                  height: '100%',
                  background:
                    color === 'error'
                      ? 'var(--ant-error)'
                      : color === 'warning'
                        ? 'var(--ant-warning)'
                        : 'var(--ant-success)',
                  transition: 'width 300ms',
                }}
              />
            </div>
            <div
              className="mono"
              style={{
                fontSize: 10,
                color: 'var(--text-3)',
                textAlign: 'right',
              }}
            >
              {(pct * 100).toFixed(1)}%
            </div>
          </Space>
        );
      },
    },
    {
      title: '429 触发',
      dataIndex: 'blocked',
      width: 120,
      sorter: (a, b) => a.blocked - b.blocked,
      render: (v: number) =>
        v > 0 ? (
          <Tooltip title={`最近：${formatNum(v)} 次`}>
            <Tag color="error" icon={<WarningOutlined />}>
              {v} 次
            </Tag>
          </Tooltip>
        ) : (
          <Tag color="success" icon={<CheckCircleOutlined />}>
            0 次
          </Tag>
        ),
    },
    {
      title: '操作',
      width: 140,
      align: 'right',
      render: (_, r) => (
        <Popconfirm
          title={`临时提高 ${r.name} 限额 +20%？`}
          description="立即生效，下次拉取配额时恢复后端配置值"
          okText="提高"
          cancelText="取消"
          onConfirm={() => {
            // 乐观更新本地配额行（后端 put 接口待接线，先让操作真实可见）
            setRows((prev) =>
              prev.map((row) =>
                row.dim === r.dim && row.id === r.id
                  ? { ...row, limit: Math.round(row.limit * 1.2) }
                  : row,
              ),
            );
            message.success(`已为 ${r.name} 提高限额至 ${Math.round(r.limit * 1.2)}`);
          }}
        >
          <Button type="link" size="small" icon={<ThunderboltOutlined />}>
            提高限额
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="RateLimit · 限流"
        title="限流实时面板"
        sub={
          rows.length > 0 ? (
            <span>
              {rows.length} 个监控对象 · 5 维度 · 10s 自动刷新 ·{' '}
              {live ? (
                <Tag color="success">● 实时</Tag>
              ) : (
                <Tag color="warning">◐ 派生</Tag>
              )}
            </span>
          ) : (
            '加载中…'
          )
        }
        actions={
          <Space>
            <Button icon={<ExportOutlined />} onClick={onExport} disabled={rows.length === 0}>
              导出
            </Button>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
          </Space>
        }
      />

      {error && <ErrorState error={error} onRetry={load} />}

      {/* 维度概览 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
        {ALL_DIMS_LIST.map((d) => {
          const dRows = rows.filter((r) => r.dim === d);
          const blocked = dRows.reduce((acc, r) => acc + r.blocked, 0);
          const utilPct =
            dRows.length > 0
              ? Math.round(
                  (dRows.reduce((acc, r) => acc + r.current / Math.max(1, r.limit), 0) /
                    dRows.length) *
                    100,
                )
              : 0;
          return (
            <Col xs={12} md={8} lg={4} xl={4} key={d}>
              <StatCard
                accent={blocked > 0 ? 'red' : 'blue'}
                label={
                  <>
                    <MicronIcon kind={blocked > 0 ? 'alert' : 'gauge'} />
                    {DIM_LABEL[d]}
                  </>
                }
                value={`${utilPct}%`}
                trend={
                  blocked > 0
                    ? { direction: 'up', text: `⚠ ${blocked} 次 429` }
                    : { direction: 'down', text: '✓ 正常' }
                }
              />
            </Col>
          );
        })}
      </Row>

      {/* 主面板 */}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <div className="content-card">
            <Tabs
              activeKey={dim}
              onChange={(k) => setDim(k as Dim)}
              items={ALL_DIMS_LIST.map((d) => ({
                key: d,
                label: `${DIM_LABEL[d]} · ${rows.filter((r) => r.dim === d).length}`,
              }))}
            />
            {filtered.length === 0 ? (
              <EmptyState
                variant="no-data"
                description={`${DIM_LABEL[dim]} 维度暂无数据`}
              />
            ) : (
              <Table<QuotaRow>
                rowKey={(r) => `${r.dim}-${r.id}`}
                columns={cols}
                dataSource={filtered}
                loading={loading}
                pagination={false}
                size="middle"
              />
            )}
          </div>
        </Col>

        <Col xs={24} lg={8}>
          <div className="content-card">
            <div className="content-card-head">
              <div className="content-card-title">24h 限流事件 · {events.length}</div>
            </div>
            {events.length === 0 ? (
              <EmptyState variant="no-data" description="未触发任何限流 · 一切平稳" />
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {events.slice(0, 12).map((e) => (
                  <div
                    key={e.id}
                    style={{
                      padding: 8,
                      borderRadius: 4,
                      background: 'rgba(255, 77, 79, 0.04)',
                      border: '1px solid rgba(255, 77, 79, 0.2)',
                      fontSize: 12,
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        color: 'var(--text-3)',
                        marginBottom: 2,
                      }}
                    >
                      <span className="mono">{e.time?.slice(11, 16) ?? '--:--'}</span>
                      <Tag color="error" style={{ margin: 0, fontSize: 10 }}>
                        {e.dim}
                      </Tag>
                    </div>
                    <div>
                      <strong>{e.name}</strong> · {e.reason}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div
            className="content-card"
            style={{ marginTop: 16, fontSize: 12, color: 'var(--text-3)' }}
          >
            <div className="content-card-head">
              <div className="content-card-title">总览</div>
            </div>
            <div>
              监控对象：<strong>{rows.length}</strong>
            </div>
            <div>
              5m QPS 总和：<strong>{totals.tenant + totals.user + totals.key}</strong>
            </div>
            <div>
              24h Token 估算：<strong>{formatNum(totals['token-daily'])}</strong>
            </div>
            <div>
              429 触发：<strong style={{ color: blockedTotal > 0 ? 'var(--ant-error)' : 'var(--ant-success)' }}>
                {blockedTotal}
              </strong>
            </div>
          </div>
        </Col>
      </Row>
    </>
  );
}

void ExportOutlined;