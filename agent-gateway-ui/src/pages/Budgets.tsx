/**
 * Budgets — 预算管理（D2 · spec §21.4）
 *
 * 设计目标：
 *   - 租户级预算配置（日/月上限 × TOKEN/MONEY）+ 告警阈值百分比
 *   - 超额动作三档可配：ALERT（告警不阻断）/ THROTTLE（降速）
 *     SUSPEND 仅显式管理员动作 + 5 分钟冷静期（自动策略只到 THROTTLE）
 *   - 告警状态展示（alertSent）+ 用量进度
 *
 * 数据：GET/POST/PUT/DELETE /v1/admin/billing/budgets（billing.ts）
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Button, Card, Form, InputNumber, Popconfirm, Progress, Select,
  Space, Table, Tag, message,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  ReloadOutlined, PlusOutlined, DeleteOutlined, WarningOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { ErrorState, EmptyState } from '../components/framework/EmptyState';
import {
  createBudget, deleteBudget, findBudget, updateBudget,
  type Budget, type UsageRecord,
} from '../lib/api/billing';
import { listCosts } from '../lib/api/billing';

export function Budgets() {
  // 成本中心下钻联动：读取 URL 查询参数（tenant 或 key）自动过滤最近用量记账
  const [searchParams, setSearchParams] = useSearchParams();
  const drillFilter = searchParams.get('tenant') ?? searchParams.get('key') ?? '';
  const [budget, setBudget] = useState<Budget | null>(null);
  const [recent, setRecent] = useState<UsageRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      // 后端 costs 接口 from/to 必填（GW-4301）——默认查最近 7 天记账
      const to = new Date().toISOString();
      const from = new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString();
      const [b, usage] = await Promise.allSettled([findBudget(), listCosts({ from, to })]);
      setBudget(b.status === 'fulfilled' ? b.value : null);
      setRecent(usage.status === 'fulfilled' ? usage.value.slice(-20).reverse() : []);
      if (b.status === 'rejected') throw b.reason;
    } catch (e: any) {
      setError(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const submit = async (v: any, mode: 'create' | 'update') => {
    setSaving(true);
    try {
      const body = {
        type: v.type,
        dailyLimit: Number(v.dailyLimit),
        monthlyLimit: Number(v.monthlyLimit),
        alertThresholdPct: Number(v.alertThresholdPct),
        suspendAction: v.suspendAction || undefined,
      };
      if (mode === 'create') await createBudget(body);
      else await updateBudget(body);
      message.success(mode === 'create' ? '预算已创建' : '预算已更新');
      form.resetFields();
      await load();
    } catch (e: any) {
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const dailyPct = budget && budget.dailyLimit > 0
    ? Math.min(100, Math.round((Number(budget.currentDailyUsed) / Number(budget.dailyLimit)) * 100))
    : 0;
  const monthlyPct = budget && budget.monthlyLimit > 0
    ? Math.min(100, Math.round((Number(budget.currentMonthlyUsed) / Number(budget.monthlyLimit)) * 100))
    : 0;

  // URL 参数过滤：tenant / key 命中记账行的租户、用户、模型或 Agent 名
  const filteredRecent = useMemo(() => {
    if (!drillFilter) return recent;
    const q = drillFilter.toLowerCase();
    return recent.filter(
      (r) =>
        r.tenant?.value?.toLowerCase().includes(q) ||
        r.user?.value?.toLowerCase().includes(q) ||
        r.model?.value?.toLowerCase().includes(q) ||
        (r.agentName ?? '').toLowerCase().includes(q),
    );
  }, [recent, drillFilter]);

  const columns: TableColumnsType<UsageRecord> = [
    { title: '时间', dataIndex: 'timestamp', width: 190, render: (v: string) => v?.replace('T', ' ').slice(0, 19) },
    { title: '模型', dataIndex: ['model', 'value'], width: 130 },
    { title: '用户', dataIndex: ['user', 'value'], width: 110 },
    { title: 'Agent', dataIndex: 'agentName', width: 130 },
    { title: '输入', dataIndex: 'tokensIn', width: 90, align: 'right' },
    { title: '输出', dataIndex: 'tokensOut', width: 90, align: 'right' },
    { title: '成本 (CNY)', dataIndex: 'cost', width: 110, align: 'right',
      render: (v: number) => <Tag color="blue">{Number(v).toFixed(4)}</Tag> },
  ];

  return (
    <>
      <PageHeader eyebrow="成本中心" title="预算管理"
        sub="租户级预算 + 告警阈值（spec §21.4）— SUSPEND 为显式管理员动作，自动策略只到 THROTTLE" />

      {error && <ErrorState error={error} onRetry={load} />}

      {drillFilter && (
        <div
          data-testid="budgets-drilldown-filter"
          style={{
            marginBottom: 16,
            padding: '8px 12px',
            background: 'var(--bg-sunken, #fafafa)',
            border: '1px solid var(--border-thin, #eee)',
            borderRadius: 6,
            fontSize: 13,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <Tag color="gold">成本中心下钻</Tag>
          <span>
            已按 <strong className="mono">{drillFilter}</strong> 过滤最近用量记账（
            {filteredRecent.length} / {recent.length} 条）
          </span>
          <a onClick={() => setSearchParams({})}>清除过滤</a>
          <Link to={`/alerts?q=${encodeURIComponent(drillFilter)}`}>查看相关告警</Link>
        </div>
      )}

      <Card
        title="当前预算"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
            <Popconfirm title="删除当前预算？" description="同时撤销 SUSPEND 冷静期（如配置）"
              onConfirm={async () => {
                try { await deleteBudget(); message.success('已删除'); await load(); }
                catch (e: any) { message.error(e?.message ?? '删除失败'); }
              }}>
              <Button danger icon={<DeleteOutlined />} disabled={!budget}>删除</Button>
            </Popconfirm>
          </Space>
        }
      >
        {budget ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Space wrap size="large">
              <Tag color="geekblue">{budget.type === 'MONEY' ? '金额预算' : 'Token 预算'}</Tag>
              <span>日上限 <strong>{Number(budget.dailyLimit).toLocaleString()}</strong></span>
              <span>月上限 <strong>{Number(budget.monthlyLimit).toLocaleString()}</strong></span>
              <Tag color={budget.alertSent ? 'red' : 'blue'} icon={budget.alertSent ? <WarningOutlined /> : undefined}>
                告警阈值 {budget.alertThreshold?.percent ?? '—'}%{budget.alertSent ? ' · 已触发' : ''}
              </Tag>
              {budget.suspendAction && (
                <Tag color={budget.suspendAction === 'THROTTLE' ? 'orange' : 'default'}>
                  超额动作 {budget.suspendAction}
                </Tag>
              )}
            </Space>
            <div>
              <div style={{ marginBottom: 4 }}>日用量 {Number(budget.currentDailyUsed).toLocaleString()} / {Number(budget.dailyLimit).toLocaleString()}</div>
              <Progress percent={dailyPct} status={dailyPct >= (budget.alertThreshold?.percent ?? Infinity) ? 'exception' : 'normal'} />
            </div>
            <div>
              <div style={{ marginBottom: 4 }}>月用量 {Number(budget.currentMonthlyUsed).toLocaleString()} / {Number(budget.monthlyLimit).toLocaleString()}</div>
              <Progress percent={monthlyPct} status={monthlyPct >= (budget.alertThreshold?.percent ?? Infinity) ? 'exception' : 'normal'} />
            </div>
          </Space>
        ) : (
          <EmptyState variant="no-data" description="未配置预算 — 在下方创建；无预算 = 无监控（BudgetGuard 不触发告警）" />
        )}
      </Card>

      <Card title={budget ? '更新预算（覆盖当前配置）' : '创建预算'} style={{ marginTop: 16 }}
        extra={budget ? <Tag>更新模式</Tag> : <Tag color="green"><PlusOutlined /> 新建</Tag>}>
        <Form form={form} layout="inline" onFinish={(v) => submit(v, budget ? 'update' : 'create')}
          initialValues={{ type: 'MONEY', alertThresholdPct: 80, suspendAction: 'ALERT' }}>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={[
              { value: 'MONEY', label: 'MONEY（金额）' },
              { value: 'TOKEN', label: 'TOKEN（token 数）' },
            ]} />
          </Form.Item>
          <Form.Item label="日上限" name="dailyLimit" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: 140 }} placeholder="≤ 月上限" />
          </Form.Item>
          <Form.Item label="月上限" name="monthlyLimit" rules={[{ required: true }]}>
            <InputNumber min={0} style={{ width: 140 }} />
          </Form.Item>
          <Form.Item label="告警阈值 %" name="alertThresholdPct"
            rules={[{ required: true }, { type: 'number', min: 1, max: 100 }]}>
            <InputNumber min={1} max={100} style={{ width: 90 }} />
          </Form.Item>
          <Form.Item label="超额动作" name="suspendAction">
            <Select style={{ width: 150 }} allowClear options={[
              { value: 'ALERT', label: 'ALERT（告警）' },
              { value: 'THROTTLE', label: 'THROTTLE（降速）' },
            ]} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={saving}>
              {budget ? '更新' : '创建'}
            </Button>
          </Form.Item>
        </Form>
        <div style={{ color: '#999', fontSize: 12, marginTop: 8 }}>
          约束：日上限 ≤ 月上限（否则 GW-4302）；阈值 ∈ [1,100]（否则 GW-4306）。SUSPEND 需管理员控制台显式操作，REST 不接受自动配置。
        </div>
      </Card>

      <Card title="最近用量记账（真实 token，单价快照）" style={{ marginTop: 16 }}>
        <Table<UsageRecord> rowKey="recordId" size="small" loading={loading}
          dataSource={filteredRecent} columns={columns} pagination={{ pageSize: 10 }} />
      </Card>
    </>
  );
}
