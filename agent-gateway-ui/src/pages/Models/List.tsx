import { useEffect, useMemo, useState } from 'react';
import {
  Table,
  Tabs,
  Button,
  Input,
  Select,
  Tag,
  Space,
  Drawer,
  Form,
  Popconfirm,
  message,
  Empty,
  Tooltip,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  DownloadOutlined,
  ReloadOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ExperimentOutlined,
  SearchOutlined,
  LockOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../../components/framework/PageHeader';
import { listModels, createModel, updateModel, deleteModel } from '../../lib/api/models';
import type { Model } from '../../lib/api/models';
import { GrayscaleDialog, GrayscaleConclusion } from '../../components/models/GrayscaleDialog';
import { fetchGrayscaleComparison } from '../../lib/api/models';
import type { GrayscaleComparison } from '../../lib/api/models';
import { BarChartOutlined } from '@ant-design/icons';
import { exportCsv } from '../../lib/export';
import { usePermission } from '../../hooks/useRole';
import { useAutoOpenCreate } from '../../hooks/useAutoOpenCreate';
import { useResourceList } from '../../hooks/useResourceList';
import { ErrorState } from '../../components/framework/EmptyState';
import { CodeSnippet } from '../../components/CodeSnippet';
import type { CodegenRequest } from '../../lib/codegen';

type FilterTab = 'all' | 'enabled' | 'gray' | 'disabled';

const STATUS_TAG: Record<string, { color: string; label: string }> = {
  enabled: { color: 'success', label: '● 启用' },
  gray: { color: 'warning', label: '◐ 灰度' },
  disabled: { color: 'error', label: '● 停用' },
};

function modelStatus(m: Model): 'enabled' | 'gray' | 'disabled' {
  if (!m.enabled) return 'disabled';
  if (m.grayWeight && m.grayWeight > 0) return 'gray';
  return 'enabled';
}

export function ModelsList() {
  const [models, setModels] = useState<Model[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<FilterTab>('all');
  const [search, setSearch] = useState('');
  const [providerFilter, setProviderFilter] = useState<string | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<Model | null>(null);
  const [grayscaleOpen, setGrayscaleOpen] = useState(false);
  const [grayscaleModel, setGrayscaleModel] = useState<Model | null>(null);
  const [compareOpen, setCompareOpen] = useState(false);
  const [compareModel, setCompareModel] = useState<Model | null>(null);
  const [comparison, setComparison] = useState<GrayscaleComparison | null>(null);
  const [compareLoading, setCompareLoading] = useState(false);
  const [form] = Form.useForm();

  const openComparison = async (m: Model) => {
    setCompareModel(m);
    setCompareOpen(true);
    setCompareLoading(true);
    try {
      setComparison(await fetchGrayscaleComparison(m.id));
    } catch {
      setComparison(null);
      message.error('灰度对比数据加载失败');
    } finally {
      setCompareLoading(false);
    }
  };

  const load = async () => {
    setLoading(true);
    try {
      const data = await listModels();
      setModels(data);
      setError('');
    } catch (e: any) {
      setModels([]);
      setError(e?.message ?? '模型列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const canCreate = usePermission('model.create');
  const canDelete = usePermission('model.delete');
  const canEdit = usePermission('model.edit');

  const providers = useMemo(
    () => Array.from(new Set(models.map((m) => m.provider))).filter(Boolean),
    [models],
  );

  const counts = useMemo(
    () => ({
      all: models.length,
      enabled: models.filter((m) => modelStatus(m) === 'enabled').length,
      gray: models.filter((m) => modelStatus(m) === 'gray').length,
      disabled: models.filter((m) => modelStatus(m) === 'disabled').length,
    }),
    [models],
  );

  const filtered = models.filter((m) => {
    const status = modelStatus(m);
    if (tab !== 'all' && status !== tab) return false;
    if (search) {
      const s = search.toLowerCase();
      if (
        !m.id.toLowerCase().includes(s) &&
        !(m.displayName?.toLowerCase().includes(s) ?? false)
      )
        return false;
    }
    if (providerFilter && m.provider !== providerFilter) return false;
    if (statusFilter && status !== statusFilter) return false;
    return true;
  });

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ enabled: true, capabilities: [], contextWindow: 8192 });
    setDrawerOpen(true);
  };

  // ⌘K Quick Action: /models?action=create 自动打开新建抽屉
  useAutoOpenCreate(openCreate);

  const openEdit = (m: Model) => {
    setEditing(m);
    form.setFieldsValue(m);
    setDrawerOpen(true);
  };

  /**
   * 把当前 drawer 表单状态拼成对应该模型 endpoint 的「测试连通性」代码：
     apiKey 不回填（password 字段），用 <YOUR_MODEL_KEY> 占位以免泄露。
   */
  const buildSampleRequest = (): CodegenRequest => {
    const v = form.getFieldsValue();
    const endpoint: string = (v.endpoint as string) || 'https://api.example.com/v1';
    const modelId: string = (v.id as string) || (editing?.id ?? 'gpt-4o');
    // endpoint 通常以 /v1 结尾，去掉再加 chat/completions
    const base = endpoint.replace(/\/+$/, '').replace(/\/v1$/, '');
    return {
      method: 'POST',
      url: `${base}/v1/chat/completions`,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer <YOUR_MODEL_KEY>',
      },
      body: {
        model: modelId,
        messages: [{ role: 'user', content: '你好，请自我介绍' }],
      },
    };
  };

  const onSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        await updateModel(editing.id, values);
        message.success('已更新');
      } else {
        if (!values.apiKey) {
          message.error('请填写 API Key');
          return;
        }
        await createModel(values);
        message.success('已创建');
      }
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  };

  const onDelete = async (id: string) => {
    try {
      await deleteModel(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '删除失败');
    }
  };

  const columns: TableColumnsType<Model> = [
    {
      title: '模型',
      dataIndex: 'displayName',
      width: '26%',
      render: (_, m) => (
        <div>
          <div style={{ fontWeight: 500 }}>{m.displayName || m.id}</div>
          <div className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
            {m.modelName ?? m.id}
          </div>
        </div>
      ),
    },
    {
      title: 'Provider',
      dataIndex: 'provider',
      width: '12%',
      render: (v) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: 'Endpoint',
      dataIndex: 'endpoint',
      width: '22%',
      render: (v) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {v}
        </span>
      ),
    },
    {
      title: 'Context',
      dataIndex: 'contextWindow',
      width: '10%',
      align: 'right',
      render: (v) => <span className="num">{v ? `${(v / 1000).toFixed(0)}k` : '—'}</span>,
    },
    {
      title: '状态',
      width: '12%',
      render: (_, m) => {
        const s = modelStatus(m);
        const tag = STATUS_TAG[s];
        return s === 'gray' ? (
          <Tag color="gold">◐ 灰度 {m.grayWeight}%</Tag>
        ) : (
          <Tag color={tag.color}>{tag.label}</Tag>
        );
      },
    },
    {
      title: '能力',
      dataIndex: 'capabilities',
      width: '10%',
      render: (caps: string[] = []) => (
        <>
          {caps.slice(0, 2).map((c) => (
            <Tag key={c} style={{ marginBottom: 2 }}>
              {c}
            </Tag>
          ))}
        </>
      ),
    },
    {
      title: '操作',
      width: '14%',
      align: 'right',
      render: (_, m) => (
        <Space size="small">
          <Tooltip title={canEdit ? '编辑模型' : '需要 ops+ 角色'}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(m)}
              disabled={!canEdit}
            >
              编辑
            </Button>
          </Tooltip>
          <Button
            type="link"
            size="small"
            icon={<ExperimentOutlined />}
            onClick={() => {
              setGrayscaleModel(m);
              setGrayscaleOpen(true);
            }}
          >
            灰度
          </Button>
          <Tooltip title="灰度组效果对比">
            <Button
              type="link"
              size="small"
              icon={<BarChartOutlined />}
              onClick={() => openComparison(m)}
            >
              对比
            </Button>
          </Tooltip>
          {canDelete ? (
            <Popconfirm
              title="确认删除？"
              description={`删除模型 ${m.id} 将同时影响路由`}
              onConfirm={() => onDelete(m.id)}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          ) : (
            <Tooltip title="需要 admin 角色">
              <Button type="link" size="small" danger icon={<LockOutlined />} disabled>
                删除
              </Button>
            </Tooltip>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Models · 模型"
        title="模型注册与路由"
        sub={`共 ${counts.all} 条 · 启用 ${counts.enabled} · 灰度 ${counts.gray} · 停用 ${counts.disabled}`}
        actions={
          <>
            <Button
              icon={<DownloadOutlined />}
              onClick={() => {
                exportCsv(
                  'models',
                  ['id', 'provider', 'displayName', 'modelName', 'endpoint', 'enabled', 'grayWeight', 'grayGroup', 'capabilities', 'contextWindow'],
                  filtered,
                );
                message.success(`已导出 ${filtered.length} 条`);
              }}
            >
              导出
            </Button>
            <Button icon={<ReloadOutlined />} onClick={load}>
              同步 Nacos
            </Button>
            {canCreate ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建模型
            </Button>
          ) : (
            <Tooltip title="需要 admin 角色">
              <Button type="primary" icon={<LockOutlined />} disabled>
                新建模型
              </Button>
            </Tooltip>
          )}
          </>
        }
      />

      <Tabs
        activeKey={tab}
        onChange={(k) => setTab(k as FilterTab)}
        style={{ marginBottom: 16 }}
        items={[
          { key: 'all', label: `全部 ${counts.all}` },
          { key: 'enabled', label: `启用 ${counts.enabled}` },
          { key: 'gray', label: `灰度 ${counts.gray}` },
          { key: 'disabled', label: `停用 ${counts.disabled}` },
        ]}
      />

      <div
        style={{
          display: 'flex',
          gap: 8,
          alignItems: 'center',
          marginBottom: 16,
          background: 'var(--bg-surface)',
          padding: '12px 16px',
          border: '1px solid var(--border-thin)',
          borderRadius: 'var(--r-lg)',
        }}
      >
        <Input
          allowClear
          placeholder="搜索 modelId / displayName"
          prefix={<SearchOutlined />}
          style={{ width: 240 }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Select
          placeholder="全部 Provider"
          allowClear
          style={{ width: 140 }}
          value={providerFilter}
          onChange={setProviderFilter}
          options={providers.map((p) => ({ value: p, label: p }))}
        />
        <Select
          placeholder="全部状态"
          allowClear
          style={{ width: 120 }}
          value={statusFilter}
          onChange={setStatusFilter}
          options={[
            { value: 'enabled', label: '已启用' },
            { value: 'gray', label: '灰度' },
            { value: 'disabled', label: '停用' },
          ]}
        />
        <div style={{ flex: 1 }} />
        <Button
          onClick={() => {
            setSearch('');
            setProviderFilter(undefined);
            setStatusFilter(undefined);
          }}
        >
          重置
        </Button>
      </div>

      {error ? (
        <ErrorState
          error={error}
          onRetry={load}
          retryLabel="重新加载"
        />
      ) : (
        <Table<Model>
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          locale={{ emptyText: <Empty description="暂无模型" /> }}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          style={{ background: 'var(--bg-surface)', borderRadius: 'var(--r-lg)' }}
        />
      )}

      <Drawer
        title={editing ? `编辑模型 · ${editing.id}` : '新建模型'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={520}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={onSubmit}>
              保存
            </Button>
          </Space>
        }
      >
        <Form layout="vertical" form={form}>
          <Form.Item label="模型 ID" name="id" rules={[{ required: true }]}>
            <Input disabled={!!editing} placeholder="如 gpt-4o" />
          </Form.Item>
          <Form.Item label="显示名称" name="displayName">
            <Input placeholder="如 GPT-4o 主力模型" />
          </Form.Item>
          <Form.Item label="Provider" name="provider" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'openai', label: 'OpenAI' },
                { value: 'anthropic', label: 'Anthropic' },
                { value: 'aliyun', label: '阿里云' },
                { value: 'deepseek', label: 'DeepSeek' },
                { value: 'zhipu', label: '智谱' },
              ]}
            />
          </Form.Item>
          <Form.Item label="Endpoint" name="endpoint" rules={[{ required: true }]}>
            <Input placeholder="https://api.example.com/v1" />
          </Form.Item>
          <Form.Item label={editing ? 'API Key（留空则不修改）' : 'API Key'} name="apiKey">
            <Input.Password placeholder="sk-..." />
          </Form.Item>
          <Form.Item label="Context Window" name="contextWindow">
            <Input type="number" placeholder="8192" />
          </Form.Item>
          <Form.Item label="能力标签" name="capabilities">
            <Select mode="tags" placeholder="tools, vision, reasoning..." />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Select
              options={[
                { value: true, label: '启用' },
                { value: false, label: '停用' },
              ]}
            />
          </Form.Item>
          <Form.Item label="灰度组（组内按权重分流）" name="grayGroup">
            <Input placeholder="留空 = 独立路由；同组模型按权重分流" />
          </Form.Item>
          <Form.Item label="灰度权重 0-100" name="grayWeight">
            <Input type="number" min={0} max={100} placeholder="0 = 非灰度" />
          </Form.Item>
        </Form>

        {/* 配置完后给用户一个使用示例：直接拿这个 endpoint 怎么调 */}
        <div
          style={{
            marginTop: 16,
            padding: 12,
            background: 'var(--bg-sunken)',
            borderRadius: 'var(--r-md)',
            border: '1px solid var(--border-thin)',
          }}
          data-testid="model-usage-snippet"
        >
          <div
            style={{
              fontSize: 11,
              fontFamily: 'var(--font-mono)',
              letterSpacing: 1,
              color: 'var(--text-3)',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            使用示例（点击右上角复制）
          </div>
          <CodeSnippet request={buildSampleRequest()} defaultLang="curl" />
        </div>
      </Drawer>

      <Drawer
        title={`灰度效果对比 · ${compareModel?.displayName ?? compareModel?.id ?? ''}`}
        open={compareOpen}
        onClose={() => setCompareOpen(false)}
        width={720}
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => compareModel && openComparison(compareModel)}>
            刷新
          </Button>
        }
      >
        {comparison && (
          <div style={{ marginBottom: 12, fontSize: 12, color: 'var(--text-2)' }}>
            数据源：
            <Tag color={comparison.source === 'metrics-store' ? 'green' : comparison.source === 'memory' ? 'orange' : 'default'}>
              {comparison.source === 'metrics-store'
                ? '● 指标库'
                : comparison.source === 'memory'
                  ? '◐ 内存（未接 PG）'
                  : '无数据源'}
            </Tag>
            组：{comparison.group} · 窗口：{comparison.from.slice(0, 19)} → {comparison.to.slice(0, 19)}
          </div>
        )}
        <Table<GrayscaleComparison['members'][number]>
          rowKey="modelId"
          size="small"
          loading={compareLoading}
          dataSource={comparison?.members ?? []}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无数据" /> }}
          columns={[
            { title: '成员', dataIndex: 'modelId', render: (_, r) => (
              <div>
                <span className="mono">{r.modelId}</span>
                {!r.enabled && <Tag style={{ marginLeft: 6 }}>停用</Tag>}
              </div>
            ) },
            { title: '权重', dataIndex: 'weight', align: 'right', render: (v: number) => `${v}%` },
            { title: '请求数', dataIndex: 'requests', align: 'right' },
            {
              title: 'P50 / P95',
              align: 'right',
              render: (_, r) => `${r.p50LatencyMs.toFixed(0)} / ${r.p95LatencyMs.toFixed(0)} ms`,
            },
            {
              title: '错误率',
              dataIndex: 'errorRate',
              align: 'right',
              render: (v: number) =>
                v > 0 ? <span style={{ color: '#cf1322' }}>{(v * 100).toFixed(1)}%</span> : '0%',
            },
            {
              title: '成本 (CNY)',
              dataIndex: 'costCny',
              align: 'right',
              render: (v: number) => v.toFixed(4),
            },
          ]}
        />
        {comparison && comparison.members.length > 0 && (
          <GrayscaleConclusion members={comparison.members} />
        )}
      </Drawer>

      {grayscaleModel && (
        <GrayscaleDialog
          open={grayscaleOpen}
          onClose={() => setGrayscaleOpen(false)}
          model={grayscaleModel}
          siblings={models}
          onApplied={load}
        />
      )}
    </>
  );
}