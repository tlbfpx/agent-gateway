import { useEffect, useState } from 'react';
import { Form, Input, Button, Space, message, Alert, Select, Tag } from 'antd';
import { PageHeader } from '../components/framework/PageHeader';
import { getApiKey, setApiKey, getTenant, setTenant, getAdminToken, setAdminToken } from '../lib/request';
import { RestartOnboardingButton } from '../components/framework/Onboarding';
import { DisplaySwitcher } from '../components/framework/DisplaySwitcher';
import { useRole, setRole, listRoles, ROLE_LABEL, ROLE_COLOR } from '../hooks/useRole';
import { getPromptCacheConfig } from '../lib/api/promptCache';
import type { PromptCacheConfig } from '../lib/api/promptCache';

export function Settings() {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const role = useRole();
  const [promptCache, setPromptCache] = useState<PromptCacheConfig | null>(null);

  // 提示缓存配置：只读尝试 GET /admin/config/gateway.llm.prompt-cache；
  // 后端暂未提供该读写 API 时保持 null（卡片降级为占位说明，不硬造写入接口）
  useEffect(() => {
    let alive = true;
    getPromptCacheConfig().then((c) => {
      if (alive) setPromptCache(c);
    });
    return () => {
      alive = false;
    };
  }, []);

  const onSave = async () => {
    try {
      const v = await form.validateFields();
      setSaving(true);
      setApiKey(v.apiKey);
      setTenant(v.tenant);
      setAdminToken(v.adminToken ?? '');
      message.success('已保存');
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const onClear = () => {
    setApiKey('');
    setTenant('primary');
    setAdminToken('');
    form.resetFields();
    message.success('已清除');
  };

  return (
    <>
      <PageHeader
        eyebrow="Settings · 设置"
        title="凭据与租户"
        sub="API Key 与租户 ID 保存在 localStorage"
      />

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="修改后将立即作用于全部后续请求，401 会自动清空。"
      />

      <div className="content-card" style={{ maxWidth: 640 }}>
        <Form
          layout="vertical"
          form={form}
          initialValues={{ apiKey: getApiKey(), tenant: getTenant(), adminToken: getAdminToken() }}
        >
          <Form.Item
            label="X-API-Key"
            name="apiKey"
            tooltip="从网关 POST /v1/admin/api-keys 签发"
          >
            <Input.Password placeholder="pk_..." />
          </Form.Item>
          <Form.Item
            label="X-Tenant-Id"
            name="tenant"
            tooltip="多租户场景必填，默认 primary"
          >
            <Input placeholder="primary" />
          </Form.Item>
          <Form.Item
            label="X-Admin-Token"
            name="adminToken"
            tooltip="管理端点独立凭据（gateway.security.admin-token）；留空 = 后端未启用管理鉴权"
          >
            <Input.Password placeholder="留空则不发送" autoComplete="new-password" />
          </Form.Item>
          <Space>
            <Button type="primary" onClick={onSave} loading={saving}>
              保存
            </Button>
            <Button onClick={onClear}>清除凭据</Button>
          </Space>
        </Form>
      </div>

      <div className="content-card" style={{ maxWidth: 640, marginTop: 16 }} data-testid="prompt-cache-card">
        <div className="content-card-head">
          <div className="content-card-title">提示缓存</div>
          {promptCache ? (
            <Tag color={promptCache.enabled ? 'green' : 'default'} style={{ margin: 0 }}>
              {promptCache.enabled ? '已启用' : '已停用'}
            </Tag>
          ) : (
            <Tag style={{ margin: 0 }}>未知</Tag>
          )}
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)', lineHeight: 2 }}>
          <div>
            <strong>配置项</strong> · <span className="mono">gateway.llm.prompt-cache</span>
          </div>
          <div>
            TTL ·{' '}
            <span className="mono">{promptCache ? promptCache.ttl : '—'}</span>
            <span style={{ marginInline: 12, opacity: 0.4 }}>|</span>
            容量上限 ·{' '}
            <span className="mono">{promptCache ? promptCache.maxEntries : '—'}</span>
          </div>
        </div>
        <Alert
          type={promptCache ? 'info' : 'warning'}
          showIcon
          style={{ marginTop: 8 }}
          message={
            promptCache
              ? '后端配置读取为只读展示；修改需在网关侧调整 gateway.llm.prompt-cache 后生效。'
              : '后端暂未提供 gateway.llm.prompt-cache 的配置读取接口，此处仅展示配置说明，无法读取当前值。'
          }
        />
      </div>

      <div className="content-card" style={{ maxWidth: 640, marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">当前角色</div>
          <Tag color={ROLE_COLOR[role]} style={{ margin: 0 }}>
            {ROLE_LABEL[role]}
          </Tag>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-3)', marginBottom: 8 }}>
          角色决定左侧菜单项与可执行操作。切换会立即生效，无需刷新。
        </div>
        <Select
          value={role}
          onChange={(v) => {
            setRole(v);
            message.success(`已切换到 ${ROLE_LABEL[v]}`);
          }}
          style={{ width: 240 }}
          options={listRoles().map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
        />
      </div>

      <div className="content-card" style={{ maxWidth: 640, marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">外观</div>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)', marginBottom: 8 }}>
          主题（浅色 / 深色 / 跟随系统）+ 密度（紧凑 / 舒适 / 宽松）
        </div>
        <DisplaySwitcher />
      </div>

      <div className="content-card" style={{ maxWidth: 640, marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">新手引导</div>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)', marginBottom: 8 }}>
          完成首次接入仅需 3 步：签发 Key → 配置模型 → 发送第一条消息
        </div>
        <RestartOnboardingButton />
      </div>

      <div className="content-card" style={{ maxWidth: 640, marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">版本信息</div>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)' }}>
          <div>
            <strong>UI</strong> · <span className="mono">v0.6.0</span>
          </div>
          <div style={{ marginTop: 4 }}>
            <strong>Gateway</strong> · <span className="mono">v0.6.0-stable</span>
          </div>
          <div style={{ marginTop: 4 }}>
            <strong>Build</strong> · <span className="mono">2026-08-17</span>
          </div>
        </div>
      </div>
    </>
  );
}