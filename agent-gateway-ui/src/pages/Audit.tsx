import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useUrlState } from '../hooks/useUrlState';
import { Button, Select, Table, Tag, Space, Empty, Input, Tooltip, message } from 'antd';
import type { TableColumnsType } from 'antd';
import {
   ReloadOutlined,
   CopyOutlined,
   SearchOutlined,
   FilterOutlined,
   DownloadOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { listAuditLogs, downloadAuditCsv } from '../lib/api/audit';
import type { AuditEntry } from '../lib/api/audit';
import { getTenant } from '../lib/request';
import { ErrorState } from '../components/framework/EmptyState';

const RESULT_TAG: Record<string, string> = {
  success: 'success',
  fail: 'error',
  deny: 'error',
  allow: 'success',
};

const PAGE_SIZE = 50;

/** range 窗口对应的毫秒数；'all' 表示全部（不发 from） */
const RANGE_MS: Record<string, number> = {
  '1h': 3_600_000,
  '24h': 24 * 3_600_000,
  '7d': 7 * 24 * 3_600_000,
  '30d': 30 * 24 * 3_600_000,
};

/** range -> from（ISO Instant）；全部返回 undefined */
export function rangeToFrom(range: string): string | undefined {
  const ms = RANGE_MS[range];
  return ms === undefined ? undefined : new Date(Date.now() - ms).toISOString();
}

export function Audit() {
  // Dashboard 活动流下钻：?keyword= 预填搜索关键字
  const [searchParams] = useSearchParams();
  const initialKeyword = searchParams.get('keyword') ?? '';
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  // Round 11 §ui-b5:筛选 URL 持久化(tenant/range/type/result/keyword)
  const [tenant, setTenant] = useUrlState('tenant', getTenant());
  const [range, setRange] = useUrlState('range', '24h');
  const [type, setType] = useUrlState('type', '' as string);
  const [result, setResult] = useUrlState('result', '' as string);
  // keyword 沿用 searchParams 预填(Dashboard 下钻支持)
  const [keyword, setKeyword] = useState(initialKeyword);
  const [offset, setOffset] = useState(0);
  const [reloadTick, setReloadTick] = useState(0);
  const reload = () => setReloadTick((t) => t + 1);

  // 服务端查询：type/result/range(from)/keyword/offset 全部下发
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const items = await listAuditLogs({
          tenant,
          type,
          result,
          from: rangeToFrom(range),
          keyword: keyword || undefined,
          limit: PAGE_SIZE,
          offset,
        });
        if (!cancelled) setEntries(items);
      } catch (e: any) {
        if (!cancelled) setError(e?.message ?? '加载失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenant, type, result, range, keyword, offset, reloadTick]);

  // 筛选变化时重置分页
  useEffect(() => {
    setOffset(0);
  }, [tenant, type, result, range, keyword]);

  // 返回仍为纯数组：结果条数 < limit 则无下一页
  const hasMore = entries.length >= PAGE_SIZE;
  const hasPrev = offset > 0;
  const pageNo = Math.floor(offset / PAGE_SIZE) + 1;

  // 当前页（服务端过滤后）统计
  const counts = {
    success: entries.filter((e) => e.result === 'success' || e.result === 'allow').length,
    fail: entries.filter((e) => e.result === 'fail' || e.result === 'deny').length,
    total: entries.length,
  };

  const cols: TableColumnsType<AuditEntry> = [
    { title: 'Event ID', dataIndex: 'eventId', width: 110, render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v?.slice(0, 8) ?? '—'}</span> },
    { title: 'Actor', dataIndex: 'actor', width: 160, render: (v) => <span style={{ fontSize: 13 }}>{v}</span> },
    {
      title: 'Type',
      dataIndex: 'type',
      width: 100,
      render: (v) => <Tag>{v}</Tag>,
    },
    { title: 'Time', dataIndex: 'time', width: 170, render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    { title: 'Resource', dataIndex: 'resource', render: (v) => <span className="mono" style={{ fontSize: 12 }}>{v}</span> },
    { title: 'Action', dataIndex: 'action', width: 140 },
    {
      title: 'Result',
      dataIndex: 'result',
      width: 100,
      render: (v) => <Tag color={RESULT_TAG[v] ?? 'default'}>{v}</Tag>,
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Audit · 审计"
        title="审计日志"
        sub={
          <Space>
            <span>租户 {tenant}</span>
            <Tag color="success">成功 {counts.success}</Tag>
            <Tag color={counts.fail > 0 ? 'error' : 'default'}>失败 {counts.fail}</Tag>
            <span>共 {counts.total} 条 · append-only</span>
          </Space>
        }
        actions={
          <Space>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索 actor/resource/action/eventId"
              style={{ width: 280 }}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button
              icon={<DownloadOutlined />}
              onClick={async () => {
                try {
                  await downloadAuditCsv({ tenant, type, result, from: rangeToFrom(range), keyword: keyword || undefined, limit: 10000 });
                  message.success('导出已开始');
                } catch (e) {
                  message.error(e instanceof Error ? e.message : '导出失败');
                }
              }}
              data-testid="audit-export-btn"
            >
              导出 CSV
            </Button>
          </Space>
        }
      />

      {error && <ErrorState error={error} onRetry={reload} />}

      {!error && (
        <>
          <Space style={{ marginBottom: 16 }} wrap>
            <Select
              value={tenant}
              onChange={setTenant}
              style={{ width: 180 }}
              options={[
                { value: 'primary', label: 'primary · 主租户' },
                { value: 'tenant-b', label: 'tenant-b' },
                { value: 'tenant-c', label: 'tenant-c' },
              ]}
            />
            <Select
              allowClear
              placeholder="类型筛选"
              value={type}
              onChange={setType}
              style={{ width: 140 }}
              options={[
                { value: 'chat', label: 'chat' },
                { value: 'admin', label: 'admin' },
                { value: 'auth', label: 'auth' },
                { value: 'webhook', label: 'webhook' },
              ]}
            />
            <Select
              allowClear
              placeholder="结果筛选"
              value={result}
              onChange={setResult}
              style={{ width: 140 }}
              options={[
                { value: 'SUCCESS', label: 'success' },
                { value: 'FAILURE', label: 'fail' },
              ]}
            />
            <Select
              value={range}
              onChange={setRange}
              style={{ width: 140 }}
              options={[
                { value: '1h', label: '最近 1 小时' },
                { value: '24h', label: '最近 24 小时' },
                { value: '7d', label: '最近 7 天' },
                { value: '30d', label: '最近 30 天' },
                { value: 'all', label: '全部' },
              ]}
            />
          </Space>

          <Table
            rowKey="eventId"
            columns={cols}
            dataSource={entries}
            loading={loading}
            pagination={false}
            expandable={{
              expandedRowRender: (record) => (
                <div style={{ padding: '8px 12px', background: 'var(--bg-sunken)', borderRadius: 4 }}>
                  <Space style={{ marginBottom: 8 }}>
                    <strong style={{ fontSize: 12 }}>Detail</strong>
                    <Tooltip title="复制 JSON">
                      <Button
                        size="small"
                        type="text"
                        icon={<CopyOutlined />}
                        onClick={async () => {
                          try {
                            await navigator.clipboard.writeText(record.detail ?? '{}');
                            message.success('已复制');
                          } catch {
                            // headless / 无 clipboard 权限时静默降级，不污染控制台
                            message.warning('当前环境不支持剪贴板写入');
                          }
                        }}
                      />
                    </Tooltip>
                  </Space>
                  <pre
                    className="mono"
                    style={{
                      background: '#0F1B3D',
                      color: '#E8ECF7',
                      padding: 12,
                      borderRadius: 6,
                      fontSize: 12,
                      maxHeight: 240,
                      overflow: 'auto',
                      margin: 0,
                    }}
                  >
                    {JSON.stringify(
                      {
                        eventId: record.eventId,
                        actor: record.actor,
                        type: record.type,
                        resource: record.resource,
                        action: record.action,
                        result: record.result,
                        time: record.time,
                        detail: record.detail,
                      },
                      null,
                      2,
                    )}
                  </pre>
                </div>
              ),
            }}
            locale={{ emptyText: <EmptyState variant="no-data" description="暂无审计日志" /> }}
          />

          <Space style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end' }} wrap>
            <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
              第 {pageNo} 页 · 本页 {entries.length} 条（服务端分页，每页 {PAGE_SIZE} 条）
            </span>
            <Button
              data-testid="audit-prev-page"
              disabled={!hasPrev || loading}
              onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
            >
              上一页
            </Button>
            <Button
              data-testid="audit-next-page"
              disabled={!hasMore || loading}
              onClick={() => setOffset(offset + PAGE_SIZE)}
            >
              下一页
            </Button>
          </Space>
        </>
      )}
    </>
  );
}