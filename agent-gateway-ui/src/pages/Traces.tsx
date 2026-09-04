/**
 * Traces — 调用链追踪(spec 2026-08-19 §6.1)
 *
 * 列表页:筛选(时间/操作/错误/耗时/租户)+ TraceSummary 行,30s 自动刷新。
 * 详情页:瀑布图(水平条按 startTime 对齐、宽度=占比、错误红色高亮)+ 属性面板。
 * 未配置持久化存储(503)→ 引导页。
 */
import { Alert, Button, Empty, Input, InputNumber, Modal, Popover, Select, Space, Spin, Switch, Table, Tabs, Tag, Tooltip, Form, message } from 'antd';
import { ReloadOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useUrlState } from '../hooks/useUrlState';
import { PageLoading } from '../components/framework/PageLoading';
import {
  getTraceDetail,
  listTraces,
  spanLabel,
  type SpanRecord,
  type TraceSummary,
} from '../lib/api/traces';
import { replayTrace, replayDiff } from '../lib/api/replay';
import { ApiError } from '../lib/request';

const RANGE_OPTIONS = [
  { value: '15m', label: '最近 15 分钟' },
  { value: '1h', label: '最近 1 小时' },
  { value: '6h', label: '最近 6 小时' },
  { value: '24h', label: '最近 24 小时' },
  { value: '7d', label: '最近 7 天' },
];

const OPERATION_OPTIONS = [
  { value: 'gateway.chat', label: '对话请求' },
  { value: 'llm.call', label: '模型调用' },
  { value: 'agent.call', label: 'Agent 调用' },
  { value: 'auth.verify', label: '认证' },
];

function fmtDuration(ms: number): string {
  if (ms < 1) return '<1ms';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('zh-CN', { hour12: false });
}

export function Traces() {
  const [params, setParams] = useSearchParams();
  const traceId = params.get('traceId');

  return traceId ? (
    <TraceDetail traceId={traceId} onBack={() => setParams({})} />
  ) : (
    <TraceList onSelect={(id) => setParams({ traceId: id })} />
  );
}

// ================= 列表页 =================

function TraceList({ onSelect }: { onSelect: (id: string) => void }) {
  // Round 11 §ui-b5:筛选 URL 持久化(刷新/分享保留状态)
  const [range, setRange] = useUrlState('range', '1h');
  const [operation, setOperation] = useUrlState('operation', '' as string);
  const [errorOnly, setErrorOnly] = useUrlState('errorOnly', false);
  const [minDuration, setMinDuration] = useUrlState('minDuration', 0 as number);
  const [tenantId, setTenantId] = useUrlState('tenantId', '' as string);
  const [rows, setRows] = useState<TraceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useUrlState('autoRefresh', true);

  const load = useCallback(async () => {
    try {
      setError(null);
      const data = await listTraces({
        range,
        operation: operation || undefined,
        errorOnly: errorOnly || undefined,
        minDurationMs: minDuration > 0 ? minDuration : undefined,
        tenantId: tenantId || undefined,
        limit: 100,
      });
      setRows(data);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '加载失败');
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [range, operation, errorOnly, minDuration, tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 30s 自动刷新(spec §6.1)
  useEffect(() => {
    if (!autoRefresh) return;
    const t = setInterval(() => void load(), 30_000);
    return () => clearInterval(t);
  }, [autoRefresh, load]);

  if (error && e503(error)) {
    return (
      <div style={{ padding: 24 }}>
        <Alert
          type="info"
          showIcon
          message="未配置持久化存储"
          description={
            <>调用链追踪需要 PostgreSQL + TimescaleDB。请启动{' '}
              <code>docker compose -f docker-compose.observability.yml up -d</code>{' '}
              并在网关配置 <code>observability.storage.jdbc-url</code> 后重启。
            </>
          }
        />
      </div>
    );
  }

  return (
    <div style={{ padding: 24 }}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Select options={RANGE_OPTIONS} value={range} onChange={setRange} style={{ width: 150 }} />
        <Select
          allowClear
          placeholder="操作类型"
          options={OPERATION_OPTIONS}
          value={operation}
          onChange={setOperation}
          style={{ width: 140 }}
        />
        <InputNumber
          placeholder="最短耗时 ms"
          min={0}
          value={minDuration}
          onChange={setMinDuration}
          style={{ width: 120 }}
        />
        <Input
          placeholder="租户 ID"
          value={tenantId}
          onChange={(e) => setTenantId(e.target.value)}
          style={{ width: 120 }}
          prefix={<SearchOutlined />}
        />
        <Tooltip title="只看有错误的链路">
          <span>
            错误 <Switch size="small" checked={errorOnly} onChange={setErrorOnly} />
          </span>
        </Tooltip>
        <Tooltip title="30 秒自动刷新">
          <span>
            自动 <Switch size="small" checked={autoRefresh} onChange={setAutoRefresh} />
          </span>
        </Tooltip>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
          刷新
        </Button>
      </Space>

      <Table<TraceSummary>
        rowKey="traceId"
        size="small"
        loading={loading}
        dataSource={rows}
        locale={{ emptyText: <Empty description="窗口内暂无调用链" /> }}
        onRow={(r) => ({ onClick: () => onSelect(r.traceId), style: { cursor: 'pointer' } })}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        columns={[
          {
            title: 'Trace ID',
            dataIndex: 'traceId',
            width: 180,
            render: (v: string) => <code style={{ fontSize: 12 }}>{v.slice(0, 16)}…</code>,
          },
          { title: '入口', dataIndex: 'rootSpanName', width: 110, render: spanLabel },
          {
            title: '总耗时',
            dataIndex: 'totalDurationMs',
            width: 100,
            sorter: (a, b) => a.totalDurationMs - b.totalDurationMs,
            render: (v: number) => <b>{fmtDuration(v)}</b>,
          },
          {
            title: 'Spans',
            dataIndex: 'spanCount',
            width: 80,
            align: 'center',
          },
          {
            title: 'Agent',
            dataIndex: 'agentNames',
            render: (names: string[]) =>
              names.length ? (
                <Space size={4} wrap>
                  {names.map((n) => (
                    <Tag key={n} color="geekblue">{n}</Tag>
                  ))}
                </Space>
              ) : (
                <span style={{ color: 'var(--ag-text-tertiary, #999)' }}>—</span>
              ),
          },
          {
            title: '状态',
            dataIndex: 'errorCount',
            width: 90,
            render: (n: number) =>
              n > 0 ? <Tag color="error">错误 ×{n}</Tag> : <Tag color="success">正常</Tag>,
          },
          {
            title: '开始时间',
            dataIndex: 'startTime',
            width: 110,
            render: fmtTime,
          },
        ]}
      />
    </div>
  );
}

function e503(msg: string): boolean {
  return msg.includes('503') || msg.includes('持久化存储');
}

// ================= 详情页(瀑布图) =================

function TraceDetail({ traceId, onBack }: { traceId: string; onBack: () => void }) {
  const [spans, setSpans] = useState<SpanRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<SpanRecord | null>(null);
  const [replayOpen, setReplayOpen] = useState(false);
  const [replayResult, setReplayResult] = useState<any>(null);
  const [replaying, setReplaying] = useState(false);
  const [diffAgainst, setDiffAgainst] = useState<string>('');
  const [diffResult, setDiffResult] = useState<any>(null);

  useEffect(() => {
    getTraceDetail(traceId)
      .then((d) => setSpans(d.spans))
      .catch((e) => setError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, [traceId]);

  const { totalMs, startMs } = useMemo(() => {
    if (spans.length === 0) return { totalMs: 0, startMs: 0 };
    const starts = spans.map((s) => new Date(s.startTime).getTime());
    const ends = spans.map((s) => (s.endTime ? new Date(s.endTime).getTime() : new Date(s.startTime).getTime()));
    return {
      startMs: Math.min(...starts),
      totalMs: Math.max(...ends) - Math.min(...starts) || 1,
    };
  }, [spans]);

  // 瀑布图行:根 span 在顶,子 span 按层级缩进
  const ordered = useMemo(() => {
    const byParent = new Map<string | null, SpanRecord[]>();
    for (const s of spans) {
      const key = s.parentSpanId ?? null;
      byParent.set(key, [...(byParent.get(key) ?? []), s]);
    }
    const out: { span: SpanRecord; depth: number }[] = [];
    const walk = (parent: string | null, depth: number) => {
      for (const s of (byParent.get(parent) ?? []).sort(
        (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime(),
      )) {
        out.push({ span: s, depth });
        walk(s.spanId, depth + 1);
      }
    };
    walk(null, 0);
    // 孤儿(父不在列表)也显示
    const seen = new Set(out.map((o) => o.span.spanId));
    for (const s of spans) if (!seen.has(s.spanId)) out.push({ span: s, depth: 0 });
    return out;
  }, [spans]);

  if (loading) return <PageLoading description="加载 trace 详情…" />;
  if (error) {
    return (
      <div style={{ padding: 24 }}>
        <Alert type="error" showIcon message={error} />
        <Button style={{ marginTop: 16 }} onClick={onBack}>返回列表</Button>
      </div>
    );
  }

  return (
    <div style={{ padding: 24 }}>
      <Space style={{ marginBottom: 16 }}>
        <Button onClick={onBack}>← 返回</Button>
        <span style={{ fontSize: 16, fontWeight: 600 }}>调用链详情</span>
        <code style={{ fontSize: 12 }}>{traceId}</code>
        <Button
          size="small"
          onClick={() => {
            void navigator.clipboard.writeText(traceId).then(() => message.success('已复制 traceId'));
          }}
        >
          复制 ID
        </Button>
        <span>总耗时 <b>{fmtDuration(totalMs)}</b> · {spans.length} spans</span>
        <Button
          type="primary"
          icon={<ThunderboltOutlined />}
          onClick={() => { setReplayResult(null); setDiffResult(null); setReplayOpen(true); }}
        >
          ↻ Replay
        </Button>
      </Space>

      <div style={{
        border: '1px solid var(--ag-border, #333)',
        borderRadius: 8,
        padding: '12px 16px',
        background: 'var(--ag-bg-elevated, #111)',
      }}>
        {ordered.map(({ span, depth }) => {
          const relStart = new Date(span.startTime).getTime() - startMs;
          const dur = span.durationMs ?? 0;
          const left = (relStart / totalMs) * 100;
          const width = Math.max((dur / totalMs) * 100, 0.5);
          const isErr = span.status === 'ERROR';
          return (
            <div
              key={span.spanId}
              onClick={() => setSelected(span)}
              style={{ display: 'flex', alignItems: 'center', gap: 8, height: 34, cursor: 'pointer' }}
            >
              <span style={{ width: 200, paddingLeft: depth * 16, fontSize: 12, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {depth > 0 && '└ '}
                {spanLabel(span.name)}
                {span.attributes.agent_name && (
                  <Tag style={{ marginLeft: 4 }} color="geekblue">{span.attributes.agent_name}</Tag>
                )}
                {span.attributes.provider && (
                  <Tag style={{ marginLeft: 4 }}>{span.attributes.provider}</Tag>
                )}
              </span>
              <div style={{ flex: 1, position: 'relative', height: 14 }}>
                <Tooltip title={`${spanLabel(span.name)} · ${fmtDuration(dur)}`}>
                  <div
                    data-testid="span-bar"
                    style={{
                      position: 'absolute',
                      left: `${left}%`,
                      width: `${width}%`,
                      top: 0,
                      bottom: 0,
                      borderRadius: 3,
                      background: isErr
                        ? 'linear-gradient(90deg, #ff4d4f, #ff7875)'
                        : 'linear-gradient(90deg, #1677ff88, #1677ff)',
                      boxShadow: isErr ? '0 0 6px #ff4d4f66' : 'none',
                    }}
                  />
                </Tooltip>
              </div>
              <span style={{ width: 64, textAlign: 'right', fontSize: 12, color: isErr ? '#ff7875' : undefined }}>
                {fmtDuration(dur)}
              </span>
            </div>
          );
        })}
      </div>

      {selected && (
        <Popover
          open
          trigger="click"
          onOpenChange={(v) => !v && setSelected(null)}
          placement="bottomLeft"
          content={
            <div style={{ maxWidth: 480 }}>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>
                {spanLabel(selected.name)} <Tag>{selected.kind}</Tag>{' '}
                <Tag color={selected.status === 'ERROR' ? 'error' : 'success'}>{selected.status}</Tag>
              </div>
              <div style={{ fontSize: 12, marginBottom: 8 }}>
                span <code>{selected.spanId}</code> · parent{' '}
                <code>{selected.parentSpanId ?? '—'}</code>
              </div>
              <pre
                data-testid="span-attrs"
                style={{
                  fontSize: 11,
                  background: 'rgba(0,0,0,.3)',
                  padding: 8,
                  borderRadius: 6,
                  maxHeight: 260,
                  overflow: 'auto',
                }}
              >
                {JSON.stringify(selected.attributes, null, 2)}
              </pre>
              {selected.events.length > 0 && (
                <div style={{ fontSize: 12 }}>
                  <b>事件:</b>
                  <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
                    {selected.events.map((ev, i) => (
                      <li key={i}>{fmtTime(ev.time)} · {ev.name}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          }
        >
          <span />
        </Popover>
      )}

      {/* Replay Modal(Sprint 2 P0) */}
      <Modal
        title={`↻ Replay  ${traceId}`}
        open={replayOpen}
        onCancel={() => setReplayOpen(false)}
        footer={null}
        width={720}
      >
        <Tabs
          items={[
            {
              key: 'default',
              label: '默认重放',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <p>按原请求 1:1 重放,不修改任何参数。</p>
                  <Button
                    type="primary"
                    loading={replaying}
                    onClick={async () => {
                      setReplaying(true);
                      try {
                        const r = await replayTrace(traceId, { safeReplay: true });
                        setReplayResult(r);
                        message.success(`Replay ${r.status} (jobId=${r.jobId.slice(0, 8)}…)`);
                      } catch (e: any) { message.error(String(e)); }
                      finally { setReplaying(false); }
                    }}
                  >
                    启动 Replay
                  </Button>
                </Space>
              ),
            },
            {
              key: 'whatif',
              label: 'What-if',
              children: (
                <Form
                  layout="vertical"
                  onFinish={async (v) => {
                    setReplaying(true);
                    try {
                      const r = await replayTrace(traceId, {
                        safeReplay: true,
                        overrides: {
                          model: v.model,
                          temperature: v.temperature,
                        },
                      });
                      setReplayResult(r);
                      message.success(`What-if done ${r.status}`);
                    } catch (e: any) { message.error(String(e)); }
                    finally { setReplaying(false); }
                  }}
                >
                  <Form.Item name="model" label="model" initialValue="">
                    <Input placeholder="gpt-4o / claude-3-opus …" />
                  </Form.Item>
                  <Form.Item name="temperature" label="temperature" initialValue={0.7}>
                    <InputNumber min={0} max={2} step={0.1} />
                  </Form.Item>
                  <Button type="primary" htmlType="submit" loading={replaying}>启动 What-if</Button>
                </Form>
              ),
            },
            {
              key: 'diff',
              label: '对比',
              children: (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Input
                    placeholder="对比目标 traceId"
                    value={diffAgainst}
                    onChange={(e) => setDiffAgainst(e.target.value)}
                  />
                  <Button
                    onClick={async () => {
                      try {
                        const d = await replayDiff(traceId, diffAgainst);
                        setDiffResult(d);
                      } catch (e: any) { message.error(String(e)); }
                    }}
                  >计算 Diff</Button>
                  {diffResult && (
                    <pre style={{ background: '#f5f5f5', padding: 8, maxHeight: 240, overflow: 'auto' }}>
                      {JSON.stringify(diffResult, null, 2)}
                    </pre>
                  )}
                </Space>
              ),
            },
          ]}
        />

        {replayResult && (
          <div style={{ marginTop: 16, padding: 8, background: '#fafafa', border: '1px solid #eee' }}>
            <strong>Replay Job</strong>
            <pre style={{ marginTop: 8, fontSize: 12 }}>
{`jobId: ${replayResult.jobId}
status: ${replayResult.status}
replayTraceId: ${replayResult.replayTraceId ?? '—'}
safeReplay: ${replayResult.safeReplay}
finishedAt: ${replayResult.finishedAt ?? '—'}`}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  );
}
