import { useState } from 'react';
import { Alert, Button, Form, Input, message } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { login } from '../lib/api/auth';
import { PageHeader } from '../components/framework/PageHeader';
import { hasAdminToken } from '../lib/api/auth';

/**
 * /login Admin 登录页（Round 14 §bcrypt-auth §7 UI）。
 *
 * 极简:tenant + email + password → 调 /v1/admin/auth/login → 存 token
 * 登录成功跳转到 /dashboard（PageHeader 提供链接）。
 */
export function Login() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [form] = Form.useForm();

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      setLoading(true);
      setError('');
      const r = await login(v.tenantId, v.email, v.password);
      message.success(`已登录 · 角色 ${r.user.role}`);
      // 跳转:用 location 简单粗暴
      setTimeout(() => { window.location.href = '/admin-users'; }, 400);
    } catch (e) {
      if (e instanceof Error) setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  if (hasAdminToken()) {
    // 已登录
    return (
      <>
        <PageHeader title="Admin 登录" sub="已登录" />
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
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form.Item>
        </Form>
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 16 }}
          message="首次部署?"
          description="R12 #1 默认 AdminToken 兼容路径仍可用(任意非空 token 当 OWNER);本登录页启用后走真鉴权。"
        />
      </div>
    </>
  );
}
