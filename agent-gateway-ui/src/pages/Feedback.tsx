import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Input,
  Select,
  Space,
  Table,
  Tag,
  message,
  type TableColumnsType,
} from 'antd';
import {
  ReloadOutlined,
  SearchOutlined,
  DownloadOutlined,
  LikeOutlined,
  DislikeOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { FeedbackSummaryCard } from '../components/feedback/FeedbackSummaryCard';
import { listFeedback, type FeedbackRecord, type Sentiment } from '../lib/api/feedback';
import { getTenant } from '../lib/request';

const PAGE_SIZE = 50;

const SENTIMENT_COLOR: Record<Sentiment, string> = {
  POSITIVE: 'green',
  NEGATIVE: 'red',
  NEUTRAL: 'default',
};

const SENTIMENT_LABEL: Record<Sentiment, string> = {
  POSITIVE: '👍 正面',
  NEGATIVE: '👎 负面',
  NEUTRAL: '中性',
};

/**
 * /feedback 管理页（Round 11 §feedback-annotation §6）。
 *
 * - 顶部 4 张统计卡 + 模型分布 + Top 标签（FeedbackSummaryCard 复用）
 * - 筛选:tenant/model/sentiment/keyword
 * - 表格:分页 + 排序 + 时间倒序
 * - 操作:导出 CSV
 */
export function Feedback() {
  const [tenant, setTenant] = useState(getTenant());
  const [model, setModel] = useState<string | undefined>();
  const [sentiment, setSentiment] = useState<Sentiment | undefined>();
  const [keyword, setKeyword] = useState('');
  const [records, setRecords] = useState<FeedbackRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [reloadTick, setReloadTick] = useState(0);
  const [offset, setOffset] = useState(0);

  const reload = () => setReloadTick((t) => t + 1);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    listFeedback({ tenant, model, sentiment, keyword: keyword || undefined, limit: PAGE_SIZE, offset })
      .then((rs) => {
        if (cancelled) return;
        setRecords(rs);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [tenant, model, sentiment, keyword, offset, reloadTick]);

  const filteredCount = records.length;

  const exportCsv = () => {
    if (records.length === 0) {
      message.warning('当前无数据可导出');
      return;
    }
    const header = ['id', 'traceId', 'spanId', 'tenantId', 'userId', 'model', 'sentiment', 'score', 'comment', 'tags', 'createdAt'];
    const rows = records.map((r) => [
      String(r.id),
      r.traceId,
      r.spanId ?? '',
      r.tenantId,
      r.userId ?? '',
      r.model ?? '',
      r.sentiment,
      String(r.score),
      (r.comment ?? '').replace(/[\n\r]/g, ' '),
      (r.tags ?? []).join('|'),
      r.createdAt,
    ]);
    const csv = [header, ...rows].map((row) => row.map((c) => `"${c.replace(/"/g, '""')}"`).join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `feedback-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
    message.success(`已导出 ${records.length} 条反馈`);
  };

  const columns: TableColumnsType<FeedbackRecord> = useMemo(() => [
    {
      title: '情感',
      dataIndex: 'sentiment',
      key: 'sentiment',
      width: 100,
      render: (s: Sentiment) => <Tag color={SENTIMENT_COLOR[s]}>{SENTIMENT_LABEL[s]}</Tag>,
      filters: [
        { text: '👍 正面', value: 'POSITIVE' },
        { text: '👎 负面', value: 'NEGATIVE' },
        { text: '中性', value: 'NEUTRAL' },
      ],
      onFilter: (value, record) => record.sentiment === value,
    },
    {
      title: '分数',
      dataIndex: 'score',
      key: 'score',
      width: 60,
      render: (s: number) => (s ? s : '—'),
    },
    { title: 'Trace', dataIndex: 'traceId', key: 'traceId', width: 180, ellipsis: true },
    { title: '用户', dataIndex: 'userId', key: 'userId', width: 120, ellipsis: true },
    { title: '模型', dataIndex: 'model', key: 'model', width: 100, ellipsis: true },
    {
      title: '标签',
      dataIndex: 'tags',
      key: 'tags',
      width: 160,
      render: (tags: string[]) => (tags ?? []).map((t) => <Tag key={t}>{t}</Tag>),
    },
    {
      title: '备注',
      dataIndex: 'comment',
      key: 'comment',
      ellipsis: true,
      render: (c: string) => c || <span style={{ color: '#bbb' }}>—</span>,
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
      defaultSortOrder: 'descend',
      sorter: (a, b) => a.createdAt.localeCompare(b.createdAt),
    },
  ], []);

  return (
    <>
      <PageHeader
        eyebrow="运营"
        title="Feedback 标注"
        sub="用户对模型回复的 👍/👎 反馈 · 运营回流真实标注"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button icon={<DownloadOutlined />} onClick={exportCsv} disabled={filteredCount === 0}>
              导出 CSV
            </Button>
          </Space>
        }
      />

      <FeedbackSummaryCard tenant={tenant} model={model} />

      <Space wrap style={{ margin: '16px 0' }}>
        <span>租户:</span>
        <Input
          style={{ width: 120 }}
          value={tenant}
          onChange={(e) => setTenant(e.target.value)}
          placeholder="au"
        />
        <span>模型:</span>
        <Input
          style={{ width: 140 }}
          value={model ?? ''}
          onChange={(e) => setModel(e.target.value || undefined)}
          placeholder="(全部)"
          allowClear
        />
        <span>情感:</span>
        <Select
          style={{ width: 140 }}
          value={sentiment}
          onChange={setSentiment}
          allowClear
          placeholder="(全部)"
          options={[
            { value: 'POSITIVE', label: '👍 正面' },
            { value: 'NEGATIVE', label: '👎 负面' },
            { value: 'NEUTRAL', label: '中性' },
          ]}
        />
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索 traceId/userId/comment"
          value={keyword}
          onChange={(e) => {
            setOffset(0);
            setKeyword(e.target.value);
          }}
          style={{ width: 280 }}
          allowClear
        />
      </Space>

      {error ? (
        <EmptyState description={`加载失败: ${error}`} />
      ) : records.length === 0 && !loading ? (
        <EmptyState
          description={keyword ? '没有匹配当前筛选的反馈' : '还没有用户提交反馈;在 Chat 或 Traces 页点击 👍/👎 即可录入'}
        />
      ) : (
        <Table<FeedbackRecord>
          rowKey="id"
          columns={columns}
          dataSource={records}
          loading={loading}
          size="middle"
          pagination={{
            pageSize: PAGE_SIZE,
            current: Math.floor(offset / PAGE_SIZE) + 1,
            total: filteredCount + offset,
            onChange: (page) => setOffset((page - 1) * PAGE_SIZE),
            showSizeChanger: false,
          }}
        />
      )}
    </>
  );
}
