import { useEffect, useState } from 'react';
import {
  Button, Form, Input, Modal, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, PauseCircleOutlined, PlayCircleOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import {
  listAdmins, registerAdmin, changeAdminRole, suspendAdmin, activateAdmin, deleteAdmin,
  getAdminToken,
  type AdminRole, type AdminStatus, type AdminUser,
} from '../lib/api/admin';

const ROLE_COLOR: Record<AdminRole, string> = {
  OWNER: 'magenta',
  ADMIN: 'red',
  OPERATOR: 'blue',
  VIEWER: 'default',
};
const STATUS_COLOR: Record<AdminStatus, string> = {
  ACTIVE: 'green',
  SUSPENDED: 'orange',
  DELETED: 'default',
};

/**
 * /admin-users 管理页（Round 12 §multi-admin §6 UI）。
 *
 * 列表 + 筛选 + 创建 modal + 行内操作(暂停/激活/改角色/删除)
 */
export function AdminUsers() {
  const token = getAdminToken();
  const [records, setRecords] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [tenant, setTenant] = useState('au');
  const [role, setRole] = useState<AdminRole | undefined>();
  const [status, setStatus] = useState<AdminStatus | undefined>();
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  const reload = () => {
    setLoading(true);
    setError('');
    listAdmins(token, { tenant, role, status, limit: 200 })
      .then(setRecords)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, [tenant, role, status]);

  const onCreate = async () => {
    try {
      const v = await form.validateFields();
      setSubmitting(true);
      await registerAdmin(token, { ...v, tenantId: tenant });
      message.success('已创建');
      setModalOpen(false);
      form.resetFields();
      reload();
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const columns: TableColumnsType<AdminUser> = [
    {
      title: '邮箱', dataIndex: 'email', key: 'email', width: 220, ellipsis: true,
    },
    { title: '显示名', dataIndex: 'name', key: 'name', width: 120 },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 110,
      render: (r: AdminRole) => <Tag color={ROLE_COLOR[r]}>{r}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (s: AdminStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    {
      title: '最后登录', dataIndex: 'lastLoginAt', key: 'lastLoginAt', width: 180,
      render: (t: string) => (t ? new Date(t).toLocaleString() : <span style={{ color: '#bbb' }}>—</span>),
    },
    {
      title: '创建于', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作', key: 'actions', width: 280,
      render: (_: any, r: AdminUser) => (
        <Space size={4}>
          {r.status === 'ACTIVE' ? (
            <Button
              size="small" icon={<PauseCircleOutlined />}
              onClick={async () => {
                try { await suspendAdmin(token, r.id); message.success('已暂停'); reload(); }
                catch (e) { message.error(e instanceof Error ? e.message : String(e)); }
              }}
            >暂停</Button>
          ) : (
            <Button
              size="small" icon={<PlayCircleOutlined />}
              onClick={async () => {
                try { await activateAdmin(token, r.id); message.success('已激活'); reload(); }
                catch (e) { message.error(e instanceof Error ? e.message : String(e)); }
              }}
            >激活</Button>
          )}
          <Select
            size="small" style={{ width: 110 }}
            value={r.role}
            options={(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'] as AdminRole[]).map((x) => ({
              value: x, label: x,
            }))}
            onChange={async (newRole) => {
              try {
                await changeAdminRole(token, r.id, newRole);
                message.success('已变更角色');
                reload();
              } catch (e) {
                message.error(e instanceof Error ? e.message : String(e));
              }
            }}
          />
          {r.role !== 'OWNER' && (
            <Button
              size="small" danger icon={<DeleteOutlined />}
              onClick={async () => {
                try { await deleteAdmin(token, r.id); message.success('已删除'); reload(); }
                catch (e) { message.error(e instanceof Error ? e.message : String(e)); }
              }}
            >删除</Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="权限"
        title="Admin 用户管理"
        sub="多 Admin 账号 · RBAC · 团队成员"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
              新建 Admin
            </Button>
          </Space>
        }
      />

      <Space wrap style={{ marginBottom: 16 }}>
        <span>租户:</span>
        <Input style={{ width: 120 }} value={tenant} onChange={(e) => setTenant(e.target.value)} />
        <span>角色:</span>
        <Select
          style={{ width: 140 }} value={role} onChange={setRole} allowClear placeholder="(全部)"
          options={(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'] as AdminRole[]).map((x) => ({
            value: x, label: x,
          }))}
        />
        <span>状态:</span>
        <Select
          style={{ width: 140 }} value={status} onChange={setStatus} allowClear placeholder="(全部)"
          options={(['ACTIVE', 'SUSPENDED', 'DELETED'] as AdminStatus[]).map((x) => ({
            value: x, label: x,
          }))}
        />
      </Space>

      {error ? (
        <EmptyState description={`加载失败: ${error}`} />
      ) : (
        <Table<AdminUser>
          rowKey="id" columns={columns} dataSource={records}
          loading={loading} size="middle" pagination={{ pageSize: 50 }}
        />
      )}

      <Modal
        title="新建 Admin"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={onCreate}
        confirmLoading={submitting}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="email" label="邮箱" rules={[
            { required: true, message: '请输入邮箱' },
            { type: 'email', message: '邮箱格式不正确' },
          ]}>
            <Input placeholder="alice@example.com" />
          </Form.Item>
          <Form.Item name="name" label="显示名" rules={[{ required: true, message: '请输入显示名' }]}>
            <Input placeholder="Alice" />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select
              placeholder="选择角色"
              options={(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'] as AdminRole[]).map((x) => ({
                value: x, label: x,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
