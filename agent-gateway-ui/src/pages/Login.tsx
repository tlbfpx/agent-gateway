import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Space, Spin, message } from 'antd';
import { LockOutlined, UserOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { login, oidcStatus, oidcStartLogin, hasAdminToken } from '../lib/api/auth';
import { PageHeader } from '../components/framework/PageHeader';
import type { OidcStatus } from '../lib/api/auth';

/**
 * /login Admin 登录页（Round 14 §bcrypt-auth §7 UI + Round 23 §sso-oidc §7）。
 *
 * 两个登录入口：
 * 1. 密码登录：tenant + email + password → /v1/admin/auth/login
 * 2. 企业 SSO（spec §sso-oidc）：拉 /v1/auth/oidc/status 判断启用，
 *    启用则显示「用企业账号登录」按钮 → /v1/auth/oidc/login 拿 authorizationUrl → location.href
 */
export function Login() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [ssoStarting, setSsoStarting] = useState(false);
  const [oidc, setOidc] = useState<OidcStatus | null>(null);
  const [form] = Form.useForm();

  // 启动时探测 OIDC 是否启用；404 视为关闭
  useEffect(() => {
    oidcStatus().then(setOidc).catch(() => setOidc({ enabled: false }));
  }, []);

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      setLoading(true);
      setError('');
      const r = await login(v.tenantId, v.email, v.password);
      message.success(`已登录 · 角色 ${r.user.role}`);
      setTimeout(() => { window.location.href = '/admin-users'; }, 400);
    } catch (e) {
      if (e instanceof Error) setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const onSsoLogin = async () => {
    setSsoStarting(true);
    try {
      const r = await oidcStartLogin('/admin-users');
      // 整页跳到 IdP 登录页；callback 端 set localStorage + 跳 /admin-users
      window.location.href = r.authorizationUrl;
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'SSO 登录失败');
      setSsoStarting(false);
    }
  };

  if (hasAdminToken()) {
    return (
      <>
        <PageHeader eyebrow="权限" title="Admin 登录" sub="已登录" />
        <Alert
          type="success" showIcon
          message="已登录 · 浏览器已保存 Admin Token"
          description="可直接访问 /admin-users /teams /prompts /datasets /feedback 等管理端"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="权限"
        title="Admin 登录"
        sub="多 Admin 账号 + PBKDF2 密码哈希 + RBAC"
      />

      <div style={{ maxWidth: 420 }}>
        {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}

        {/* 企业 SSO 入口：探测中 → 加载；启用 → 按钮；关闭 → 不渲染 */}
        {oidc === null && (
          <div style={{ marginBottom: 16, color: 'var(--text-3)', fontSize: 13 }}>
            <Spin size="small" /> 正在检测企业 SSO…
          </div>
        )}
        {oidc?.enabled && (
          <div style={{ marginBottom: 16 }} data-testid="sso-section">
            <Button
              type="primary"
              size="large"
              block
              icon={<SafetyCertificateOutlined />}
              loading={ssoStarting}
              onClick={onSsoLogin}
              data-testid="sso-login-btn"
            >
              {ssoStarting ? '正在跳转企业登录…' : `用 ${oidc.displayName ?? '企业账号'} 登录`}
            </Button>
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 12 }}
              message="SSO 走 OAuth2 Authorization Code Flow，浏览器重定向到企业 IdP 登录"
            />
          </div>
        )}

        {oidc?.enabled && (
          <div style={{ textAlign: 'center', margin: '12px 0', color: 'var(--text-3)' }}>
            — 或 —
          </div>
        )}

        <Form form={form} layout="vertical" onFinish={onSubmit}>
          <Form.Item
            name="tenantId" label="租户" initialValue="au" rules={[{ required: true }]}
          >
            <Input prefix={<UserOutlined />} placeholder="au" />
          </Form.Item>
          <Form.Item
            name="email" label="邮箱" rules={[
              { required: true, message: '请输入邮箱' },
              { type: 'email', message: '邮箱格式不正确' },
            ]}
          >
            <Input prefix={<UserOutlined />} placeholder="alice@example.com" />
          </Form.Item>
          <Form.Item
            name="password" label="密码" rules={[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="********" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={loading}>
                密码登录
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </div>
    </>
  );
}