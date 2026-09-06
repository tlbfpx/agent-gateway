import { useEffect, useState } from 'react';
import { Form, Input, Button, Space, message, Alert, Select, Tag, Card } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';
import { getApiKey, setApiKey, getTenant, setTenant, getAdminToken, setAdminToken } from '../lib/request';
import { RestartOnboardingButton } from '../components/framework/Onboarding';
import { DisplaySwitcher } from '../components/framework/DisplaySwitcher';
import { useRole, setRole, listRoles, ROLE_LABEL, ROLE_COLOR } from '../hooks/useRole';
import { getPromptCacheConfig } from '../lib/api/promptCache';
import type { PromptCacheConfig } from '../lib/api/promptCache';
import { createApiKey } from '../lib/api/keys';

export function Settings() {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [creatingKey, setCreatingKey] = useState(false);
  const [issuedKey, setIssuedKey] = useState<string>('');
  const role = useRole();
  const [promptCache, setPromptCache] = useState<PromptCacheConfig | null>(null);
  const [hasApiKey, setHasApiKey] = useState<boolean>(!!getApiKey());
  const [hasAdminToken, setHasAdminToken] = useState<boolean>(!!getAdminToken());

  // 检测「已登录但无 API Key」：自助注册后常见，挡在这里体验最差
  const needsFirstKey = hasAdminToken && !hasApiKey;

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
    setHasApiKey(false);
    setHasAdminToken(false);
    setIssuedKey('');
    form.resetFields();
    message.success('已清除');
  };

  /** 自助注册后第一把 API Key 一键签发（spec §self-send-onboarding §4）。
   *  后端 POST /v1/admin/api-keys 仅要求请求体带 tenant + user，不要求 apiKey 自身。
   *  user 用当前 adminToken 对应账号的 email（如果可推导）或 fallback 'owner'。 */
  const onCreateFirstKey = async () => {
    const tenant = getTenant();
    setCreatingKey(true);
    try {
      const r = await createApiKey({ tenant, user: 'owner' });
      const key = r.created?.value ?? (r as any).apiKey?.value ?? '';
      if (!key) {
        throw new Error('后端未返回 key value');
      }
      setApiKey(key);
      setIssuedKey(key);
      setHasApiKey(true);
      form.setFieldValue('apiKey', key);
      message.success('已签发首把 API Key');
    } catch (e) {
      message.error(e instanceof Error ? e.message : '签发失败');
    } finally {
      setCreatingKey(false);
    }
  };

  const t = useT();

  return (
    <>
      <PageHeader
        eyebrow={`Settings · ${t('settings.title')}`}
        title={t('settings.title')}
        sub={t('settings.subtitle')}
      />

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={t('settings.alert')}
      />

      {needsFirstKey && (
        <Card
          data-testid="first-key-cta"
          style={{
            maxWidth: 640,
            marginBottom: 16,
            background: 'linear-gradient(135deg, #f0f5ff 0%, #fff7e6 100%)',
            borderColor: '#faad14',
          }}
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space>
              <ThunderboltOutlined style={{ color: '#faad14', fontSize: 20 }} />
              <strong style={{ fontSize: 16 }}>欢迎 · 一键签发你的第一把 API Key</strong>
            </Space>
            <div style={{ color: 'var(--text-2)', fontSize: 13, lineHeight: 1.7 }}>
              已检测到 Admin Token 但还没有 API Key。整个产品功能都依赖 Key 才能调用
              （chat、feedback、metrics、cache …）。点击下方按钮 5 秒拿到 Key，之后所有页面
              自动可用。
            </div>
            <Space wrap>
              <Button
                type="primary"
                size="large"
                icon={<ThunderboltOutlined />}
                loading={creatingKey}
                onClick={onCreateFirstKey}
                data-testid="first-key-btn"
              >
                {creatingKey ? '签发中…' : '一键签发首把 API Key'}
              </Button>
              <Button onClick={() => setHasApiKey(!!getApiKey())}>我已有 Key · 刷新状态</Button>
            </Space>
            {issuedKey && (
              <Alert
                type="success"
                showIcon
                message="Key 已签发并自动写入本地"
                description={
                  <Space direction="vertical" size={4}>
                    <code style={{ wordBreak: 'break-all' }}>
                      {issuedKey.slice(0, 16)}…{issuedKey.slice(-6)}
                    </code>
                    <span style={{ color: 'var(--text-3)', fontSize: 12 }}>
                      完整值已写入 localStorage.agent-gateway.apiKey · 下次启动自动带入
                    </span>
                  </Space>
                }
              />
            )}
          </Space>
        </Card>
      )}

      <div className="content-card" style={{ maxWidth: 640 }}>
        <Form
          layout="vertical"
          form={form}
          initialValues={{ apiKey: getApiKey(), tenant: getTenant(), adminToken: getAdminToken() }}
        >
          <Form.Item
            label={t('settings.apiKey')}
            name="apiKey"
            tooltip="从网关 POST /v1/admin/api-keys 签发"
          >
            <Input.Password placeholder="pk_..." />
          </Form.Item>
          <Form.Item
            label={t('settings.tenantId')}
            name="tenant"
            tooltip="多租户场景必填，默认 primary"
          >
            <Input placeholder={t('settings.tenantIdPh')} />
          </Form.Item>
          <Form.Item
            label={t('settings.adminTok')}
            name="adminToken"
            tooltip={t('settings.adminTokHint')}
          >
            <Input.Password placeholder={t('settings.adminTokenPh')} autoComplete="new-password" />
          </Form.Item>
          <Space>
            <Button type="primary" onClick={onSave} loading={saving}>
              {t('settings.save')}
            </Button>
            <Button onClick={onClear}>{t('settings.clear')}</Button>
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