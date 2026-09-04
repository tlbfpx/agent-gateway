/**
 * Policies — 策略中心（RBAC 闭环）
 *
 * 功能：
 *   - 规则列表：按优先级降序，allow/deny 区分着色
 *   - 新建 / 编辑：Drawer 表单（主体 / 资源 / 动作 / 决定 / 优先级 / 备注）
 *   - 启用 / 停用：开关
 *   - 删除：Popconfirm
 *   - 规则排序提示："优先级大的先匹配"
 *
 * 注：测试环境仅验证渲染与编辑流，CRUD 真实生效需后端接口。
 */
import { useEffect, useState } from 'react';
import {
  Button,
  Table,
  Tag,
  Space,
  Drawer,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Popconfirm,
  message,
  Tooltip,
  Empty,
} from 'antd';
import type { TableColumnsType } from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ReloadOutlined,
  SafetyOutlined,
  ExportOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { policiesApi, getLocalStore, type Policy, type PolicyInput } from '../lib/api/policies';
import { EmptyState, ErrorState } from '../components/framework/EmptyState';
import { exportCsv } from '../lib/export';
import { useAutoOpenCreate } from '../hooks/useAutoOpenCreate';

export function Policies() {
  const [data, setData] = useState<Policy[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<Policy | null>(null);
  const [form] = Form.useForm<PolicyInput>();

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const list = await policiesApi.list();
      // 优先用本地缓存里的（mock 模式下保留用户的 CRUD 结果）
      const local = getLocalStore();
      const merged = local.length > list.length && list.length === 5 ? local : list;
      setData(merged);
    } catch (e: any) {
      setError(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const sorted = [...data].sort((a, b) => b.priority - a.priority);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      priority: 100,
      subject: { kind: 'role', value: '' },
      resource: { kind: '*', pattern: '*' },
      action: 'invoke',
      effect: 'allow',
      enabled: true,
    } as PolicyInput);
    setDrawerOpen(true);
  };

  // ⌘K Quick Action: /policies?action=create 自动打开新建规则抽屉
  useAutoOpenCreate(openCreate);

  const openEdit = (p: Policy) => {
    setEditing(p);
    form.setFieldsValue(p);
    setDrawerOpen(true);
  };

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      if (editing) {
        await policiesApi.update(editing.id, v);
        message.success('已更新');
      } else {
        await policiesApi.create(v);
        message.success('已创建');
      }
      setDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '操作失败');
    }
  };

  const onDelete = async (id: string) => {
    try {
      await policiesApi.delete(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '删除失败');
    }
  };

  const onExport = () => {
    exportCsv(
      'rbac-policies',
      ['id', 'name', 'priority', 'subject_kind', 'subject_value', 'resource_kind', 'resource_pattern', 'action', 'effect', 'enabled'],
      sorted.map((p) => ({
        id: p.id,
        name: p.name,
        priority: p.priority,
        subject_kind: p.subject.kind,
        subject_value: p.subject.value,
        resource_kind: p.resource.kind,
        resource_pattern: p.resource.pattern,
        action: p.action,
        effect: p.effect,
        enabled: p.enabled,
      })),
    );
  };

  const cols: TableColumnsType<Policy> = [
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 90,
      sorter: (a, b) => a.priority - b.priority,
      defaultSortOrder: 'descend',
      render: (v: number) => (
        <strong style={{ fontFamily: 'var(--font-mono)', color: 'var(--brand-amber)' }}>
          {v}
        </strong>
      ),
    },
    {
      title: '名称',
      dataIndex: 'name',
      render: (v: string, r) => (
        <Space size={6}>
          <Tag color={r.effect === 'allow' ? 'success' : 'error'} style={{ margin: 0 }}>
            {r.effect.toUpperCase()}
          </Tag>
          <strong>{v}</strong>
        </Space>
      ),
    },
    {
      title: '主体',
      dataIndex: 'subject',
      width: 200,
      render: (s: Policy['subject']) => (
        <Tooltip title="匹配来源">
          <span className="mono" style={{ fontSize: 12 }}>
            {s.kind}:<strong>{s.value}</strong>
          </span>
        </Tooltip>
      ),
    },
    {
      title: '资源',
      dataIndex: 'resource',
      width: 220,
      render: (r: Policy['resource']) => (
        <Tooltip title="匹配目标">
          <span className="mono" style={{ fontSize: 12 }}>
            {r.kind}:<strong>{r.pattern}</strong>
          </span>
        </Tooltip>
      ),
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 100,
      render: (v: string) => <Tag>{v}</Tag>,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v: boolean) => (
        <Tag color={v ? 'success' : 'default'}>{v ? 'ON' : 'OFF'}</Tag>
      ),
    },
    {
      title: '操作',
      width: 140,
      align: 'right',
      render: (_, r) => (
        <Space size={4}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Popconfirm title={`删除规则 "${r.name}"？`} onConfirm={() => onDelete(r.id)}>
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Policies · 策略"
        title="RBAC 策略中心"
        sub={`${data.length} 条规则 · 优先级降序匹配 · 默认 deny`}
        actions={
          <Space>
            <Button icon={<ExportOutlined />} onClick={onExport} disabled={data.length === 0}>
              导出
            </Button>
            <Button icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建规则
            </Button>
          </Space>
        }
      />

      {error && <ErrorState error={error} onRetry={load} />}

      {!error && data.length === 0 ? (
        <EmptyState
          variant="no-data"
          description="尚无策略规则"
          icon={<SafetyOutlined />}
          action={{ label: '+ 创建第一条规则', onClick: openCreate }}
        />
      ) : (
        <>
          <div
            style={{
              padding: 12,
              marginBottom: 16,
              background: 'var(--bg-sunken)',
              border: '1px solid var(--border-thin)',
              borderRadius: 6,
              fontSize: 12,
              color: 'var(--text-3)',
              lineHeight: 1.6,
            }}
          >
            <SafetyOutlined /> 策略匹配规则：
            <strong style={{ color: 'var(--text-1)' }}> 优先级大的先匹配；相同优先级按列表顺序；默认 deny（拒绝）</strong>。
            详情预览可访问 <a href="/rbac">RBAC 预览</a> 页面，用 actor/action/resource 试判定。
          </div>

          <div className="content-card">
            <Table<Policy>
              rowKey="id"
              columns={cols}
              dataSource={sorted}
              loading={loading}
              pagination={false}
              size="middle"
              locale={{ emptyText: <EmptyState variant="no-data" description="暂无规则" /> }}
            />
          </div>
        </>
      )}

      <Drawer
        title={editing ? `编辑规则 · ${editing.name}` : '新建策略规则'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={560}
        destroyOnHidden
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={onSubmit}>
              保存
            </Button>
          </Space>
        }
      >
        <Form<PolicyInput> layout="vertical" form={form}>
          <Form.Item label="规则名称" name="name" rules={[{ required: true }]}>
            <Input placeholder="如 ops 只读权限" />
          </Form.Item>
          <Form.Item label="优先级（数字越大越先匹配）" name="priority" rules={[{ required: true }]}>
            <InputNumber min={1} max={10000} style={{ width: '100%' }} />
          </Form.Item>

          <Space.Compact style={{ width: '100%' }}>
            <Form.Item label="主体类型" name={['subject', 'kind']} rules={[{ required: true }]} style={{ width: '40%' }}>
              <Select
                options={[
                  { value: 'role', label: '角色' },
                  { value: 'actor', label: '用户' },
                  { value: 'tenant', label: '租户' },
                ]}
              />
            </Form.Item>
            <Form.Item label="主体值" name={['subject', 'value']} rules={[{ required: true }]} style={{ width: '60%', marginLeft: 8 }}>
              <Input placeholder="如 admin / alice@primary / primary" />
            </Form.Item>
          </Space.Compact>

          <Space.Compact style={{ width: '100%' }}>
            <Form.Item label="资源类型" name={['resource', 'kind']} rules={[{ required: true }]} style={{ width: '40%' }}>
              <Select
                options={[
                  { value: '*', label: '任意' },
                  { value: 'model', label: '模型' },
                  { value: 'agent', label: 'Agent' },
                  { value: 'skill', label: 'Skill' },
                ]}
              />
            </Form.Item>
            <Form.Item label="资源模式" name={['resource', 'pattern']} rules={[{ required: true }]} style={{ width: '60%', marginLeft: 8 }}>
              <Input placeholder="如 gpt-4o 或 * 或 tenant-*/models/*" />
            </Form.Item>
          </Space.Compact>

          <Space.Compact style={{ width: '100%' }}>
            <Form.Item label="动作" name="action" rules={[{ required: true }]} style={{ width: '50%' }}>
              <Select
                options={[
                  { value: '*', label: '* (任意)' },
                  { value: 'read', label: 'read · 读' },
                  { value: 'write', label: 'write · 写' },
                  { value: 'invoke', label: 'invoke · 调用' },
                  { value: 'admin', label: 'admin · 管理' },
                ]}
              />
            </Form.Item>
            <Form.Item label="决定" name="effect" rules={[{ required: true }]} style={{ width: '50%', marginLeft: 8 }}>
              <Select
                options={[
                  { value: 'allow', label: '✓ allow' },
                  { value: 'deny', label: '✗ deny' },
                ]}
              />
            </Form.Item>
          </Space.Compact>

          <Form.Item label="备注" name="description">
            <Input.TextArea rows={2} placeholder="规则用途说明（可选）" />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch checkedChildren="ON" unCheckedChildren="OFF" />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
}