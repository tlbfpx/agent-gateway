import { useEffect, useState } from 'react';
import {
  Button, Form, Input, Modal, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, UserAddOutlined, UserDeleteOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import {
  listTeams, createTeam, addTeamMember, removeTeamMember, transferTeamOwnership,
  listAdmins, getAdminToken,
  type AdminUser, type Team,
} from '../lib/api/admin';

const token = () => getAdminToken();

/**
 * /teams 团队管理页（Round 12 §multi-admin §6 UI）。
 *
 * 团队列表 + 创建 + 成员管理 + 转让所有权
 */
export function Teams() {
  const [teams, setTeams] = useState<Team[]>([]);
  const [admins, setAdmins] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [tenant, setTenant] = useState('au');
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [memberTeam, setMemberTeam] = useState<Team | null>(null);
  const [memberAdminId, setMemberAdminId] = useState<number | null>(null);
  const [xferTeam, setXferTeam] = useState<Team | null>(null);
  const [xferAdminId, setXferAdminId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    Promise.all([listTeams(token(), tenant), listAdmins(token(), { tenant, limit: 200 })])
      .then(([ts, ad]) => { setTeams(ts); setAdmins(ad); })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, [tenant]);

  const onCreate = async () => {
    try {
      const v = await createForm.validateFields();
      setBusy(true);
      await createTeam(token(), { ...v, tenantId: tenant });
      message.success('已创建团队');
      setCreateOpen(false);
      createForm.resetFields();
      reload();
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const onAddMember = async () => {
    if (!memberTeam || memberAdminId == null) return;
    try {
      setBusy(true);
      await addTeamMember(token(), memberTeam.id, memberAdminId);
      message.success('已添加成员');
      setMemberTeam(null);
      setMemberAdminId(null);
      reload();
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const onTransfer = async () => {
    if (!xferTeam || xferAdminId == null) return;
    try {
      setBusy(true);
      await transferTeamOwnership(token(), xferTeam.id, xferAdminId);
      message.success('已转让所有权');
      setXferTeam(null);
      setXferAdminId(null);
      reload();
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const adminName = (id: number) => {
    const a = admins.find((x) => x.id === id);
    return a ? `${a.name} (${a.email})` : `#${id}`;
  };

  const columns: TableColumnsType<Team> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '名称', dataIndex: 'name', key: 'name', width: 200, ellipsis: true },
    {
      title: 'Owner', dataIndex: 'ownerId', key: 'ownerId', width: 200,
      render: (id: number) => adminName(id),
    },
    {
      title: '成员数', dataIndex: 'size', key: 'size', width: 100,
      render: (s: number) => <Tag>{s} 人</Tag>,
    },
    {
      title: '成员', dataIndex: 'memberIds', key: 'memberIds',
      render: (ids: number[]) => (
        <Space wrap size={4}>
          {ids.length === 0 ? <span style={{ color: '#bbb' }}>—</span> : ids.map((id) => (
            <Tag
              key={id} closable
              onClose={async (e) => {
                e.preventDefault();
                if (!confirm(`移除成员 ${adminName(id)}?`)) return;
                try {
                  await removeTeamMember(token(), memberTeam?.id ?? 0, id);
                  message.success('已移除');
                  reload();
                } catch (err) {
                  message.error(err instanceof Error ? err.message : String(err));
                }
              }}
            >
              {adminName(id)}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '创建于', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作', key: 'actions', width: 220,
      render: (_: any, t: Team) => (
        <Space size={4}>
          <Button
            size="small" icon={<UserAddOutlined />}
            onClick={() => { setMemberTeam(t); setMemberAdminId(null); }}
          >添加成员</Button>
          <Button
            size="small" icon={<SwapOutlined />}
            onClick={() => { setXferTeam(t); setXferAdminId(null); }}
          >转让</Button>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="权限"
        title="团队管理"
        sub="Owner / 成员 / 转让所有权"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建团队
            </Button>
          </Space>
        }
      />

      <Space style={{ marginBottom: 16 }}>
        <span>租户:</span>
        <Input style={{ width: 120 }} value={tenant} onChange={(e) => setTenant(e.target.value)} />
      </Space>

      {error ? (
        <EmptyState description={`加载失败: ${error}`} />
      ) : (
        <Table<Team>
          rowKey="id" columns={columns} dataSource={teams}
          loading={loading} size="middle" pagination={{ pageSize: 50 }}
        />
      )}

      <Modal
        title="新建团队"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreate}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="name" label="团队名" rules={[{ required: true, message: '请输入团队名' }]}>
            <Input placeholder="platform-team" />
          </Form.Item>
          <Form.Item name="ownerId" label="Owner" rules={[{ required: true, message: '请选择 Owner' }]}>
            <Select
              placeholder="选择 Owner Admin"
              showSearch optionFilterProp="label"
              options={admins.map((a) => ({
                value: a.id,
                label: `${a.name} (${a.email}) — ${a.role}`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={memberTeam ? `添加成员到 ${memberTeam.name}` : '添加成员'}
        open={memberTeam !== null}
        onCancel={() => setMemberTeam(null)}
        onOk={onAddMember}
        confirmLoading={busy}
        okText="添加"
        cancelText="取消"
      >
        <Select
          style={{ width: '100%' }}
          placeholder="选择 Admin"
          showSearch optionFilterProp="label"
          value={memberAdminId ?? undefined}
          onChange={setMemberAdminId}
          options={admins
            .filter((a) => memberTeam && !memberTeam.memberIds.includes(a.id) && a.id !== memberTeam.ownerId)
            .map((a) => ({ value: a.id, label: `${a.name} (${a.email}) — ${a.role}` }))}
        />
      </Modal>

      <Modal
        title={xferTeam ? `转让 ${xferTeam.name} 所有权` : '转让所有权'}
        open={xferTeam !== null}
        onCancel={() => setXferTeam(null)}
        onOk={onTransfer}
        confirmLoading={busy}
        okText="确认转让"
        cancelText="取消"
      >
        <p style={{ color: 'var(--text-2)', marginBottom: 16 }}>
          转让后旧 Owner 自动成为成员,目标用户需 OWNER 权限。
        </p>
        <Select
          style={{ width: '100%' }}
          placeholder="选择新 Owner"
          showSearch optionFilterProp="label"
          value={xferAdminId ?? undefined}
          onChange={setXferAdminId}
          options={admins
            .filter((a) => xferTeam && a.id !== xferTeam.ownerId)
            .map((a) => ({ value: a.id, label: `${a.name} (${a.email}) — ${a.role}` }))}
        />
      </Modal>
    </>
  );
}
