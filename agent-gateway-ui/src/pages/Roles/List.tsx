import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Form, Input, Modal, Popconfirm, Segmented, Select,
  Space, Table, Tag, message,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { PageHeader } from '../../components/framework/PageHeader';
import {
  createRole,
  deleteRole,
  listRoles,
  updateRole,
  type RbacPermission,
  type RbacRole,
} from '../../lib/api/roles';
import { listModels } from '../../lib/api/models';
import { listAgents } from '../../lib/api/agents';

/** 权限行的表单形态（type 决定后端 sealed 子类型的映射） */
interface PermRow {
  type: 'agent' | 'model' | 'skill';
  agentName?: string;
  skills?: string[];        // 技能白名单（tags 多选）
  models?: string[];        // 模型 id 多选
  skillName?: string;
}

interface RoleFormValues {
  name: string;
  description?: string;
  perms: PermRow[];
}

/** RbacPermission[] → 表单行（编辑回显） */
function toPermRows(perms: RbacPermission[] | undefined): PermRow[] {
  if (!perms?.length) return [{ type: 'agent' }];
  return perms.map((p) => {
    if (p.models?.length) return { type: "model", models: p.models };
    if (p.skillName) return { type: 'skill', agentName: p.agentName, skillName: p.skillName };
    return { type: 'agent', agentName: p.agentName, skills: p.allowedSkills ?? [] };
  });
}

/** 表单行 → RbacPermission[]（提交；空白行丢弃） */
function toPermissions(rows: PermRow[] | undefined): RbacPermission[] {
  if (!rows) return [];
  return rows
    .filter((r) => {
      if (!r?.type) return false;
      if (r.type === 'model') return (r.models?.length ?? 0) > 0;
      if (r.type === 'skill') return !!r.agentName && !!r.skillName;
      return !!r.agentName;
    })
    .map((r) => {
      if (r.type === 'model') return { models: r.models };
      if (r.type === 'skill') return { agentName: r.agentName, skillName: r.skillName };
      return {
        agentName: r.agentName,
        allowedSkills: r.skills ?? [],
      };
    });
}

function permSummary(perms: RbacPermission[] | undefined): string {
  if (!perms?.length) return '—';
  return perms
    .slice(0, 3)
    .map((p) =>
      p.models?.length ? `模型×${p.models.length}`
      : p.skillName ? `${p.agentName}#${p.skillName}`
      : p.agentName ?? '?')
    .join('、') + (perms.length > 3 ? ` 等 ${perms.length} 项` : '');
}

export function RolesList() {
  const [rows, setRows] = useState<RbacRole[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<RbacRole | null>(null);
  const [modelIds, setModelIds] = useState<string[]>([]);
  const [agentNames, setAgentNames] = useState<string[]>([]);
  const [agentSkills, setAgentSkills] = useState<Record<string, string[]>>({});
  const [form] = Form.useForm<RoleFormValues>();

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await listRoles());
    } catch (e: any) {
      message.error(e?.message ?? '加载角色失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
    // 下拉数据源：模型列表 + Agent 注册表（Agent 名与技能白名单全部结构化，杜绝自由输入）
    listModels().then((ms) => setModelIds(ms.map((m) => m.id))).catch(() => undefined);
    listAgents()
      .then((ags) => {
        setAgentNames(ags.map((a) => a.name));
        setAgentSkills(Object.fromEntries(ags.map((a) => [a.name, a.skills ?? []])));
      })
      .catch(() => undefined);
  }, [refresh]);

  // 关键修复：Modal 打开「之后」再回填表单（destroyOnClose + setFieldsValue 时序坑导致编辑弹窗全空白）
  useEffect(() => {
    if (!modalOpen) return;
    if (editing) {
      form.setFieldsValue({
        name: editing.name,
        description: editing.description,
        perms: toPermRows(editing.permissions),
      });
    } else {
      form.resetFields();
    }
  }, [modalOpen, editing, form]);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
  };
  const openEdit = (role: RbacRole) => {
    setEditing(role);
    setModalOpen(true);
  };
  const closeModal = () => {
    setModalOpen(false);
    setEditing(null);
    form.resetFields();
  };

  const onSubmit = async () => {
    const v = await form.validateFields();
    const permissions = toPermissions(v.perms);
    if (!permissions.length) {
      message.warning('至少配置一条有效权限（Agent 名 / 模型 / 技能任选）');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await updateRole(editing.id.value, {
          name: v.name, description: v.description, permissions,
        });
        message.success('角色已更新');
      } else {
        await createRole({ name: v.name, description: v.description, permissions });
        message.success('角色已创建');
      }
      closeModal();
      await refresh();
    } catch (e: any) {
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async (role: RbacRole) => {
    try {
      await deleteRole(role.id.value);
      message.success(`角色 ${role.name} 已删除`);
      await refresh();
    } catch (e: any) {
      message.error(e?.message ?? '删除失败');
    }
  };

  const modelOptions = useMemo(
    () => modelIds.map((id) => ({ value: id, label: id })), [modelIds]);
  const agentOptions = useMemo(
    () => agentNames.map((n) => ({ value: n, label: n })), [agentNames]);
  /** 指定 agent 的技能选项（未注册的 agent 给空列表，Select 以 tags 模式兜底显示既有值） */
  const skillsOf = useCallback(
    (agent?: string) => (agentSkills[agent ?? ''] ?? []).map((sk) => ({ value: sk, label: sk })),
    [agentSkills]);

  return (
    <>
      <PageHeader
        eyebrow="RBAC · 角色管理"
        title="角色"
        sub="租户维度的角色定义与权限集合（spec §19.2）。权限支持 Agent / 模型 / 技能三种类型。"
      />
      <Card
        title="角色列表"
        extra={
          <Space>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建角色
            </Button>
            <Button onClick={() => void refresh()}>刷新</Button>
          </Space>
        }
      >
        <Table<RbacRole>
          rowKey={(r) => r.id.value}
          loading={loading}
          dataSource={rows}
          pagination={{ pageSize: 10 }}
          columns={[
            { title: 'ID', dataIndex: ['id', 'value'], width: 190, ellipsis: true },
            { title: '名称', dataIndex: 'name', width: 140 },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            {
              title: '权限',
              dataIndex: 'permissions',
              width: 280,
              render: (perms: RbacPermission[]) => (
                <Space size={4} wrap>
                  <Tag color="blue">{perms?.length ?? 0} 项</Tag>
                  <span>{permSummary(perms)}</span>
                </Space>
              ),
            },
            {
              title: '操作',
              width: 150,
              render: (_, role) => (
                <Space>
                  <Button size="small" type="link" onClick={() => openEdit(role)}>
                    编辑
                  </Button>
                  <Popconfirm title={`删除角色 ${role.name}？`} onConfirm={() => void onDelete(role)}>
                    <Button size="small" type="link" danger>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editing ? `编辑角色 · ${editing.name}` : '新建角色'}
        open={modalOpen}
        onCancel={closeModal}
        onOk={() => void onSubmit()}
        confirmLoading={saving}
        width={680}
        okText="保存"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" initialValues={{ perms: [{ type: 'agent' }] }}>
          <Form.Item label="名称" name="name" rules={[{ required: true, max: 64, message: '必填，≤64 字符' }]}>
            <Input placeholder="如 developer" />
          </Form.Item>
          <Form.Item label="描述" name="description" rules={[{ max: 256 }]}>
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>

          <Form.Item label="权限集合（可混合 Agent / 模型 / 技能三种类型，全部下拉选择）" required style={{ marginBottom: 0 }}>
            <Form.List name="perms">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name }) => (
                    <div key={key} className="perm-row"
                      style={{ display: 'flex', gap: 8, alignItems: 'center',
                        marginBottom: 8, padding: '8px 10px',
                        border: '1px solid var(--ant-color-border-secondary, #eee)',
                        borderRadius: 8, flexWrap: 'wrap' }}>
                      <Form.Item name={[name, 'type']} noStyle rules={[{ required: true }]}>
                        <Segmented size="small" options={[
                          { value: 'agent', label: 'Agent' },
                          { value: 'model', label: '模型' },
                          { value: 'skill', label: '技能' },
                        ]} />
                      </Form.Item>

                      <Form.Item noStyle shouldUpdate={(a, b) =>
                        a.perms?.[name]?.type !== b.perms?.[name]?.type
                        || a.perms?.[name]?.agentName !== b.perms?.[name]?.agentName}>
                        {({ getFieldValue }) => {
                          const type = getFieldValue(['perms', name, 'type']) as PermRow['type'];
                          const agent = getFieldValue(['perms', name, 'agentName']) as string | undefined;
                          if (type === 'model') {
                            return (
                              <Form.Item name={[name, 'models']} noStyle
                                rules={[{ required: true, message: '至少选一个模型' }]}>
                                <Select mode="multiple" style={{ minWidth: 300 }} maxTagCount="responsive"
                                  placeholder="从模型列表选择（可搜索）" options={modelOptions}
                                  showSearch optionFilterProp="label" />
                              </Form.Item>
                            );
                          }
                          if (type === 'skill') {
                            return (
                              <>
                                <Form.Item name={[name, 'agentName']} noStyle
                                  rules={[{ required: true, message: '选择 Agent' }]}>
                                  <Select style={{ width: 160 }} showSearch
                                    placeholder="选择 Agent" options={agentOptions}
                                    optionFilterProp="label" notFoundContent="注册表为空" />
                                </Form.Item>
                                <Form.Item name={[name, 'skillName']} noStyle
                                  rules={[{ required: true, message: '选择技能' }]}>
                                  <Select style={{ width: 160 }} showSearch
                                    placeholder="选择技能" options={skillsOf(agent)}
                                    optionFilterProp="label" notFoundContent="该 Agent 无已登记技能" />
                                </Form.Item>
                              </>
                            );
                          }
                          return (
                            <>
                              <Form.Item name={[name, 'agentName']} noStyle
                                rules={[{ required: true, message: '选择 Agent' }]}>
                                <Select style={{ width: 170 }} showSearch
                                  placeholder="选择 Agent" options={agentOptions}
                                  optionFilterProp="label" notFoundContent="注册表为空" />
                              </Form.Item>
                              <Form.Item name={[name, 'skills']} noStyle>
                                <Select mode="tags" style={{ minWidth: 260 }} maxTagCount="responsive"
                                  placeholder="技能白名单（不选 = 全部）" options={skillsOf(agent)}
                                  optionFilterProp="label" suffixIcon={null}
                                  tokenSeparators={[',']} />
                              </Form.Item>
                            </>
                          );
                        }}
                      </Form.Item>

                      <Button type="text" danger size="small" icon={<DeleteOutlined />}
                        style={{ marginLeft: 'auto' }} onClick={() => remove(name)}
                        aria-label="删除此条权限" />
                    </div>
                  ))}
                  <Button type="dashed" block icon={<PlusOutlined />}
                    onClick={() => add({ type: 'agent' })}>
                    添加权限
                  </Button>
                </>
              )}
            </Form.List>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
