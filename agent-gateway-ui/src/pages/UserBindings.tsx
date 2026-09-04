import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Checkbox, Input, Space, Table, Tag, message } from 'antd';
import { PageHeader } from '../components/framework/PageHeader';
import {
  bindRole,
  listRoles,
  listUserRoles,
  previewPolicy,
  unbindRole,
  type RbacRole,
} from '../lib/api/roles';

export function UserBindings() {
  const [userId, setUserId] = useState('');
  const [queryUser, setQueryUser] = useState('');
  const [allRoles, setAllRoles] = useState<RbacRole[]>([]);
  const [userRoles, setUserRoles] = useState<RbacRole[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async (target: string) => {
    if (!target) return;
    setLoading(true);
    try {
      const [roles, bound] = await Promise.all([listRoles(), listUserRoles(target)]);
      setAllRoles(roles);
      setUserRoles(bound);
    } catch (e: any) {
      message.error(e?.message ?? '加载绑定失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void listRoles().then(setAllRoles).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (queryUser) void refresh(queryUser);
  }, [queryUser, refresh]);

  const isBound = (roleId: string) =>
    userRoles.some((r) => r.id.value === roleId);

  const toggle = async (role: RbacRole, checked: boolean) => {
    if (!queryUser) {
      message.warning('请先查询用户');
      return;
    }
    try {
      if (checked) {
        await bindRole(queryUser, role.id.value);
        message.success(`已绑定 ${role.name}`);
      } else {
        await unbindRole(queryUser, role.id.value);
        message.success(`已解绑 ${role.name}`);
      }
      await refresh(queryUser);
    } catch (e: any) {
      message.error(e?.message ?? '操作失败');
    }
  };

  const onPreview = async () => {
    if (!queryUser) return;
    try {
      const pp = await previewPolicy(queryUser);
      message.info(
        `可用 Agent：${pp.allowedAgents.length ? pp.allowedAgents.join('、') : '（无）'}；可用模型：${
          pp.allowedModels.length ? pp.allowedModels.map((m) => m.value).join('、') : '（无）'
        }`,
        5,
      );
    } catch (e: any) {
      message.error(e?.message ?? '预览失败');
    }
  };

  return (
    <>
      <PageHeader
        eyebrow="RBAC · 用户绑定"
        title="用户角色绑定"
        sub="查询用户 → 勾选/取消角色（spec §19.3）。重复绑定返回 409（GW-1011），解绑不存在返回 404（GW-1013）。"
      />
      <Card title="绑定管理">
        <Space style={{ marginBottom: 16 }} wrap>
          <Input.Search
            placeholder="输入 UserId，如 u-1"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            onSearch={(v) => setQueryUser(v.trim())}
            enterButton="查询"
            style={{ width: 320 }}
          />
          <Button onClick={() => void onPreview()} disabled={!queryUser}>
            预览权限
          </Button>
        </Space>
        <Table<RbacRole>
          rowKey={(r) => r.id.value}
          loading={loading}
          dataSource={allRoles}
          pagination={false}
          columns={[
            { title: 'ID', dataIndex: ['id', 'value'], width: 200 },
            { title: '角色', dataIndex: 'name', width: 160 },
            { title: '描述', dataIndex: 'description', ellipsis: true },
            {
              title: '已绑定',
              width: 120,
              render: (_, role) => (
                <Tag color={isBound(role.id.value) ? 'green' : 'default'}>
                  {isBound(role.id.value) ? '是' : '否'}
                </Tag>
              ),
            },
            {
              title: '绑定操作',
              width: 120,
              render: (_, role) => (
                <Checkbox
                  checked={isBound(role.id.value)}
                  onChange={(e) => void toggle(role, e.target.checked)}
                />
              ),
            },
          ]}
        />
      </Card>
    </>
  );
}
