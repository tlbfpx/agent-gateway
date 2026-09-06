import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  PlusOutlined,
  ReloadOutlined,
  PoweroffOutlined,
  ApiOutlined,
  SearchOutlined,
  RobotOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';
import {
  listRegisteredAgents,
  registerAgent,
  updateAgent,
  deleteAgent,
  toggleAgentAvailability,
  testAgentConnection,
  checkAgentName,
} from '../lib/api/agents';
import type {
  AgentRegistration,
  AgentRegistrationInput,
  ListAgentsQuery,
} from '../lib/api/agents';
import { relTime } from '../lib/format';
import { EmptyState, ErrorState } from '../components/framework/EmptyState';
import { usePermission } from '../hooks/useRole';
import { useAutoOpenCreate } from '../hooks/useAutoOpenCreate';
import { LockOutlined } from '@ant-design/icons';

/** 路由约定：
 *  - /discovery：只读服务发现浏览（自 Nacos 拉取）
 *  - /agents  ：管理员注册/编辑/启停/删除
 */

type FormShape = {
  name: string;
  description: string;
  endpoint: string;
  version: string;
  enabled: boolean;
  heartbeatTimeoutSec?: number;
  tags?: string[];
  skills?: string[];
};

const SOURCE_LABEL: Record<AgentRegistration['source'], { text: string; color: string }> = {
  static: { text: '静态', color: 'default' },
  nacos: { text: 'Nacos', color: 'cyan' },
  manual: { text: '手工', color: 'blue' },
  kubernetes: { text: 'K8s', color: 'purple' },
};

export function Agents() {
  const t = useT();
  const [data, setData] = useState<AgentRegistration[]>([]);
  const canRegister = usePermission('agent.register');
  const canEdit = usePermission('agent.edit');
  const canDelete = usePermission('agent.delete');
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [query, setQuery] = useState<ListAgentsQuery>({ page: 1, pageSize: 20 });
  const [keyword, setKeyword] = useState('');
  const [sourceFilter, setSourceFilter] = useState<AgentRegistration['source'] | undefined>();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerMode, setDrawerMode] = useState<'create' | 'edit'>('create');
  const [editing, setEditing] = useState<AgentRegistration | null>(null);
  const [form] = Form.useForm<FormShape>();
  const [submitting, setSubmitting] = useState(false);

  const [testResult, setTestResult] = useState<Record<string, { ok: boolean; message?: string }>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listRegisteredAgents(query);
      setData(res.items);
      setTotal(res.total);
      setError('');
    } catch (e: any) {
      setData([]);
      setTotal(0);
      setError(e?.message ?? 'Agent 列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    load();
  }, [load]);

  const onSearch = () => {
    setQuery((q) => ({ ...q, q: keyword.trim() || undefined, page: 1 }));
  };
  const onSourceFilter = (s: AgentRegistration['source'] | undefined) => {
    setSourceFilter(s);
    setQuery((q) => ({ ...q, source: s, page: 1 }));
  };

  const openCreate = () => {
    setDrawerMode('create');
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      heartbeatTimeoutSec: 30,
      tags: [],
      skills: [],
    } as unknown as FormShape);
    setDrawerOpen(true);
  };

  // ⌘K Quick Action: /agents?action=create 自动打开注册抽屉
  useAutoOpenCreate(openCreate);

  const openEdit = (rec: AgentRegistration) => {
    setDrawerMode('edit');
    setEditing(rec);
    form.setFieldsValue({
      name: rec.name,
      description: rec.description,
      endpoint: rec.endpoint,
      version: rec.version,
      enabled: rec.enabled,
      heartbeatTimeoutSec: rec.heartbeatTimeoutSec ?? 30,
      tags: rec.tags,
      skills: rec.skills,
    });
    setDrawerOpen(true);
  };

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      setSubmitting(true);
      const body: AgentRegistrationInput = {
        name: v.name.trim(),
        description: v.description.trim(),
        endpoint: v.endpoint.trim(),
        version: v.version.trim(),
        enabled: v.enabled,
        heartbeatTimeoutSec: v.heartbeatTimeoutSec,
        tags: v.tags,
        skills: v.skills,
      };
      if (drawerMode === 'create') {
        await registerAgent(body);
        message.success('已注册');
      } else if (editing) {
        await updateAgent(editing.id, body);
        message.success('已保存');
      }
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return; // antd validation
      message.error(e?.message ?? '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const onDelete = async (rec: AgentRegistration) => {
    try {
      await deleteAgent(rec.id);
      message.success(`已删除 ${rec.name}`);
      load();
    } catch (e: any) {
      message.error(e?.message ?? '删除失败');
    }
  };

  const onToggle = async (rec: AgentRegistration, enabled: boolean) => {
    try {
      await toggleAgentAvailability(rec.id, enabled);
      message.success(enabled ? '已启用' : '已停用');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  };

  const onTest = async (rec: AgentRegistration) => {
    setTestResult((prev) => ({ ...prev, [rec.id]: { ok: false, message: '测试中…' } }));
    try {
      const r = await testAgentConnection(rec.id);
      setTestResult((prev) => ({ ...prev, [rec.id]: { ok: r.ok, message: r.message } }));
      message.success(r.ok ? `${rec.name} 通了（${r.latencyMs ?? '?'}ms）` : '连通失败');
    } catch (e: any) {
      setTestResult((prev) => ({ ...prev, [rec.id]: { ok: false, message: e?.message ?? '失败' } }));
      message.error('测试失败');
    }
  };

  const columns: TableColumnsType<AgentRegistration> = useMemo(
    () => [
      {
        title: '名称',
        dataIndex: 'name',
        render: (v: string, r) => (
          <Space size="small">
            <strong>{v}</strong>
            <Tag color="default">{r.version}</Tag>
          </Space>
        ),
      },
      {
        title: '端点',
        dataIndex: 'endpoint',
        ellipsis: true,
        render: (v: string) => (
          <span className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
            {v}
          </span>
        ),
      },
      {
        title: '来源',
        dataIndex: 'source',
        width: 110,
        render: (s: AgentRegistration['source']) => (
          <Tag color={SOURCE_LABEL[s].color}>{SOURCE_LABEL[s].text}</Tag>
        ),
      },
      {
        title: '技能',
        dataIndex: 'skills',
        render: (v: string[]) =>
          v?.length ? (
            <Space size={[4, 4]} wrap>
              {v.map((s) => (
                <Tag key={s}>{s}</Tag>
              ))}
            </Space>
          ) : (
            <span style={{ color: 'var(--text-3)' }}>—</span>
          ),
      },
      {
        title: '心跳',
        dataIndex: 'lastSeenAt',
        width: 140,
        render: (v?: string, r?: AgentRegistration) => (
          <Tooltip title={`超时间隔 ${r?.heartbeatTimeoutSec ?? 30}s`}>
            <Space size={6}>
              <Badge status={r?.available ? 'success' : 'default'} />
              <span style={{ fontSize: 12, color: 'var(--text-3)' }}>{v ? relTime(v) : '从未'}</span>
            </Space>
          </Tooltip>
        ),
      },
      {
        title: '启用',
        dataIndex: 'enabled',
        width: 90,
        render: (v: boolean, r) => (
          <Switch
            checked={v}
            size="small"
            onChange={(checked) => onToggle(r, checked)}
            checkedChildren="ON"
            unCheckedChildren="OFF"
          />
        ),
      },
      {
        title: '操作',
        width: 220,
        align: 'right',
        render: (_, r) => (
          <Space size={4}>
            <Button
              type="link"
              size="small"
              icon={<ApiOutlined />}
              onClick={() => onTest(r)}
              aria-label={`测试 ${r.name}`}
            >
              测试
            </Button>
            <Tooltip title={canEdit ? '' : '需要 ops+ 角色'}>
              <Button
                type="link"
                size="small"
                onClick={() => openEdit(r)}
                aria-label={`编辑 ${r.name}`}
                disabled={!canEdit}
              >
                编辑
              </Button>
            </Tooltip>
            {canDelete ? (
              <Popconfirm
                title={`删除 ${r.name}？`}
                description="删除后无法恢复，且会下挂此 Agent 的所有路由。"
                okText="删除"
                okType="danger"
                cancelText="取消"
                onConfirm={() => onDelete(r)}
              >
                <Button type="link" size="small" danger aria-label={`删除 ${r.name}`}>
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
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const sourceStats = useMemo(() => {
    const map: Record<string, number> = {};
    for (const r of data) map[r.source] = (map[r.source] ?? 0) + 1;
    return map;
  }, [data]);

  return (
    <>
      <PageHeader
        eyebrow={`Agents · ${'Agent 注册'}`}
        title={t('agents.title')}
        sub={t('agents.subtitle').replace('{total}', String(total)).replace('{enabled}', String(data.filter((d) => d.enabled).length)).replace('{count}', String(data.length))}
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            {canRegister ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              注册新 Agent
            </Button>
          ) : (
            <Tooltip title="需要 admin 角色">
              <Button type="primary" icon={<LockOutlined />} disabled>
                注册新 Agent
              </Button>
            </Tooltip>
          )}
          </Space>
        }
      />

      <div className="content-card" style={{ marginBottom: 16 }}>
        <Space wrap size={12} style={{ width: '100%' }}>
          <Input
            allowClear
            prefix={<SearchOutlined style={{ color: 'var(--text-3)' }} />}
            placeholder="按名称 / 端点 / tag 搜索"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={onSearch}
            style={{ width: 280 }}
            aria-label="搜索 Agent"
          />
          <Select
            placeholder="来源"
            allowClear
            style={{ width: 140 }}
            value={sourceFilter}
            onChange={(v) => onSourceFilter(v)}
            options={[
              { value: 'static', label: '静态' },
              { value: 'nacos', label: 'Nacos' },
              { value: 'manual', label: '手工' },
              { value: 'kubernetes', label: 'K8s' },
            ]}
          />
          <Button onClick={onSearch}>搜索</Button>

          <span style={{ flex: 1 }} />

          <Space size={6}>
            <span style={{ color: 'var(--text-3)', fontSize: 12 }}>当前页来源分布：</span>
            {Object.entries(sourceStats).map(([s, n]) => (
              <Tag key={s} color={SOURCE_LABEL[s as AgentRegistration['source']]?.color}>
                {SOURCE_LABEL[s as AgentRegistration['source']]?.text ?? s} · {n}
              </Tag>
            ))}
          </Space>
        </Space>
      </div>

      {Object.entries(testResult).some(([, v]) => v.ok === false && v.message) && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="部分 Agent 连通异常，请检查端点可达性"
          closable
        />
      )}

      <div className="content-card">
        {error ? (
          <ErrorState error={error} onRetry={load} />
        ) : data.length === 0 ? (
          <EmptyState
            variant="no-data"
            description={loading ? '加载中…' : '尚无注册的 Agent'}
            icon={<RobotOutlined />}
            action={
              !loading
                ? { label: '+ 注册第一个 Agent', onClick: openCreate }
                : undefined
            }
          />
        ) : (
          <Table<AgentRegistration>
            rowKey="id"
            columns={columns}
            dataSource={data}
            loading={loading}
            size="middle"
            pagination={{
              current: query.page ?? 1,
              pageSize: query.pageSize ?? 20,
              total,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (page, pageSize) => setQuery((q) => ({ ...q, page, pageSize })),
            }}
          />
        )}
      </div>

      <Drawer
        title={drawerMode === 'create' ? '注册新 Agent' : `编辑 · ${editing?.name ?? ''}`}
        width={520}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        destroyOnClose
        extra={
          <Tooltip title="保存将立即生效并触发全集群同步">
            <Tag color="processing">实时同步</Tag>
          </Tooltip>
        }
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" loading={submitting} onClick={onSubmit}>
              {drawerMode === 'create' ? '注册' : '保存'}
            </Button>
          </Space>
        }
      >
        <Form<FormShape> form={form} layout="vertical" preserve={false}>
          <Form.Item
            label="名称"
            name="name"
            rules={[
              { required: true, message: '名称必填' },
              { pattern: /^[a-zA-Z][a-zA-Z0-9_-]*$/, message: '字母开头，仅含字母数字-_' },
              // 实时查重：checkAgentName 之前零调用，接通到表单校验（400ms 防抖）
              {
                validator: async (_, value: string) => {
                  if (drawerMode !== 'create' || !value || !/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(value)) {
                    return;
                  }
                  await new Promise((r) => setTimeout(r, 400));
                  try {
                    const r = await checkAgentName(value);
                    if (!r.available) {
                      throw new Error(r.suggestion ? `名称已被占用，建议：${r.suggestion}` : '名称已被占用');
                    }
                  } catch (e) {
                    // 查重接口不可达时放行，由后端提交时兜底
                    if (e instanceof Error && e.message.includes('占用')) throw e;
                  }
                },
              },
            ]}
            hasFeedback
          >
            <Input placeholder="例如 weather-mcp" disabled={drawerMode === 'edit'} />
          </Form.Item>
          <Form.Item label="描述" name="description" rules={[{ required: true }]}>
            <Input.TextArea rows={2} placeholder="用途、技能简介" />
          </Form.Item>
          <Form.Item
            label="端点"
            name="endpoint"
            rules={[{ required: true }, { type: 'url', message: '请输入合法的 URL' }]}
          >
            <Input placeholder="https://agent.example.com/a2a" className="mono" />
          </Form.Item>
          <Form.Item label="版本" name="version" rules={[{ required: true }]}>
            <Input placeholder="例如 1.4.2" className="mono" />
          </Form.Item>
          <Form.Item label="标签" name="tags">
            <Select mode="tags" tokenSeparators={[',']} placeholder="逗号或回车分隔" />
          </Form.Item>
          <Form.Item label="技能" name="skills">
            <Select mode="tags" tokenSeparators={[',']} placeholder="例如 weather,search,sql" />
          </Form.Item>
          <Form.Item label="心跳超时（秒）" name="heartbeatTimeoutSec">
            <InputNumber min={5} max={600} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="默认启用" name="enabled" valuePropName="checked">
            <Switch checkedChildren="ON" unCheckedChildren="OFF" />
          </Form.Item>
        </Form>
      </Drawer>

      <div style={{ marginTop: 16, color: 'var(--text-3)', fontSize: 12 }}>
        <PoweroffOutlined /> 提示：禁用后路由仍生效但所有到该 Agent 的流量将被 503 拦截。
      </div>
    </>
  );
}
