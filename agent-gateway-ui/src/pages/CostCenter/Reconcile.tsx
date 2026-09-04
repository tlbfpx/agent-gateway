/**
 * CostCenter/Reconcile — 用量对账页（Round6）
 *
 * MVP 范围：
 *  - antd PageHeader + DatePicker.RangePicker + Table
 *  - 数据源：getVirtualKeyUsage（复用 admin/virtual-keys/:id/usage）
 *  - 后续 round7 引入 tenant-level reconcile endpoint
 *  - 默认单租户 primary 视图
 *  - 列：tenant × user × agentName × 当日 cost（聚合 by date） × 累计 cost（聚合 all-time）
 *  - 不引入 Chart（MVP 只要表格 + 过滤）
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Row,
  Col,
  DatePicker,
  Button,
  Table,
  Tag,
  Space,
  Empty,
  Tooltip,
  message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ReloadOutlined,
  DownloadOutlined,
  ReconciliationOutlined,
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { PageHeader } from '../../components/framework/PageHeader';
import { getVirtualKeyUsage } from '../../lib/api/keys';
import type { UsageRecord } from '../../lib/api/keys';
import { exportCsv } from '../../lib/export';
import { ErrorState } from '../../components/framework/EmptyState';

interface DayRow {
  key: string;
  tenant: string;
  user: string;
  agentName: string;
  date: string;
  dailyCost: number;
  cumulativeCost: number;
  calls: number;
}

function startOfDay(d: Dayjs) {
  return d.startOf('day').toISOString();
}
function endOfDay(d: Dayjs) {
  return d.endOf('day').toISOString();
}

/** 本地日期字符串 (YYYY-MM-DD)，避免依赖 dayjs 模块导出 */
function isoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function Reconcile() {
  // 默认 from/to = 今天 00:00 ~ 23:59
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [
    dayjs().startOf('day'),
    dayjs().endOf('day'),
  ]);
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<DayRow[]>([]);
  const [error, setError] = useState<string>('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // MVP：复用第一个 api-key 的 usage 通道拉数据；
      // 后续 round7 接入 /admin/reconcile 时改为 tenant-level 端点。
      const usage = await getVirtualKeyUsage('pk_live_01HMZT7W3JKAQ', {
        from: startOfDay(range[0]),
        to: endOfDay(range[1]),
      });
      setRows(aggregateByDate(usage));
    } catch (e: any) {
      setError(e?.message ?? '对账数据加载失败');
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => {
    void load();
  }, [load]);

  const onExport = () => {
    exportCsv(
      `reconcile-${isoDate(range[0].toDate())}-${isoDate(range[1].toDate())}`,
      ['date', 'tenant', 'user', 'agentName', 'calls', 'daily_cost_cny', 'cumulative_cost_cny'],
      rows.map((r) => ({
        date: r.date,
        tenant: r.tenant,
        user: r.user,
        agentName: r.agentName,
        calls: r.calls,
        daily_cost_cny: r.dailyCost.toFixed(4),
        cumulative_cost_cny: r.cumulativeCost.toFixed(4),
      })),
    );
    message.success('对账明细已导出 CSV');
  };

  const total = rows.reduce((acc, r) => acc + r.dailyCost, 0);

  const columns: TableColumnsType<DayRow> = [
    {
      title: '日期',
      dataIndex: 'date',
      width: 130,
      sorter: (a, b) => a.date.localeCompare(b.date),
      defaultSortOrder: 'descend',
      render: (v: string) => <span className="mono">{v}</span>,
    },
    {
      title: '租户',
      dataIndex: 'tenant',
      width: 110,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
      filters: uniqueValues(rows.map((r) => r.tenant)).map((v) => ({ text: v, value: v })),
      onFilter: (val, r) => r.tenant === val,
    },
    {
      title: '用户',
      dataIndex: 'user',
      width: 180,
      render: (v: string) => <span className="mono" style={{ fontSize: 12 }}>{v}</span>,
    },
    {
      title: 'Agent / 资源',
      dataIndex: 'agentName',
      render: (v: string) => <Tag color="purple">{v}</Tag>,
    },
    {
      title: '调用次数',
      dataIndex: 'calls',
      width: 110,
      align: 'right',
      sorter: (a, b) => a.calls - b.calls,
      render: (v: number) => <span className="num">{v}</span>,
    },
    {
      title: '当日成本 (¥)',
      dataIndex: 'dailyCost',
      width: 150,
      align: 'right',
      sorter: (a, b) => a.dailyCost - b.dailyCost,
      render: (v: number, r) => (
        <Tooltip title={`单次 ¥${r.calls ? (v / r.calls).toFixed(4) : '0.0000'}`}>
          <strong style={{ color: 'var(--brand-amber)' }}>¥{v.toFixed(2)}</strong>
        </Tooltip>
      ),
    },
    {
      title: '累计成本 (¥)',
      dataIndex: 'cumulativeCost',
      width: 150,
      align: 'right',
      render: (v: number) => (
        <Tooltip title="自首次计费起的累计值">
          <span className="num" style={{ color: 'var(--text-2)' }}>¥{v.toFixed(2)}</span>
        </Tooltip>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Cost · 对账"
        title="用量对账"
        sub={
          <Space size={8}>
            <Tag color="default">租户 primary</Tag>
            {rows.length > 0 && <span>· 共 {rows.length} 行 · 合计 ¥{total.toFixed(2)}</span>}
          </Space>
        }
        actions={
          <Space>
            <DatePicker.RangePicker
              value={range}
              onChange={(v) => {
                if (v && v[0] && v[1]) setRange([v[0], v[1]]);
              }}
              data-testid="reconcile-range"
              allowClear={false}
            />
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading} data-testid="reconcile-query">
              查询
            </Button>
            <Button
              icon={<DownloadOutlined />}
              onClick={onExport}
              disabled={rows.length === 0}
              data-testid="reconcile-export"
            >
              导出对账明细
            </Button>
          </Space>
        }
      />

      {error && <ErrorState error={error} onRetry={load} />}

      <Row gutter={[16, 16]}>
        <Col xs={24}>
          <div className="content-card">
            <div className="content-card-head">
              <div className="content-card-title">
                <ReconciliationOutlined /> 对账明细 · {isoDate(range[0].toDate())} ~{' '}
                {isoDate(range[1].toDate())}
              </div>
              <span style={{ fontSize: 12, color: 'var(--text-3)' }}>
                按 (date, tenant, user, agentName) 聚合
              </span>
            </div>
            <Table<DayRow>
              rowKey="key"
              columns={columns}
              dataSource={rows}
              loading={loading}
              pagination={rows.length > 20 ? { pageSize: 20, showTotal: (t) => `共 ${t} 行` } : false}
              size="middle"
              locale={{
                emptyText: (
                  <Empty
                    description={
                      rows.length === 0 && !loading
                        ? '所选时间段内无对账数据'
                        : '加载中…'
                    }
                  />
                ),
              }}
            />
          </div>
        </Col>
      </Row>
    </>
  );
}

/** 把 UsageRecord[] 按 (date, tenant, user, agentName) 聚合，返回 DayRow[]，并按日期排序 + 计算累计 */
function aggregateByDate(records: UsageRecord[]): DayRow[] {
  const buckets = new Map<string, DayRow>();
  for (const r of records) {
    const date = (r.timestamp ?? '').slice(0, 10);
    if (!date) continue;
    const tenant = r.tenant?.value ?? 'unknown';
    const user = r.user?.value ?? 'unknown';
    const agentName = r.agentName ?? 'unknown';
    const key = `${date}|${tenant}|${user}|${agentName}`;
    const cur = buckets.get(key) ?? {
      key,
      date,
      tenant,
      user,
      agentName,
      dailyCost: 0,
      cumulativeCost: 0,
      calls: 0,
    };
    cur.dailyCost += Number(r.cost ?? 0);
    cur.calls += 1;
    buckets.set(key, cur);
  }
  const arr = Array.from(buckets.values());
  arr.sort((a, b) => a.date.localeCompare(b.date));
  // 计算累计成本（按日期升序累加当日）
  let sum = 0;
  for (const r of arr) {
    sum += r.dailyCost;
    r.cumulativeCost = sum;
  }
  return arr;
}

function uniqueValues<T>(xs: T[]): T[] {
  return Array.from(new Set(xs));
}