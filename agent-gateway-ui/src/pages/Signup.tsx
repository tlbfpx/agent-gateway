import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Space, Typography, message } from 'antd';
import { LockOutlined, MailOutlined, ShopOutlined } from '@ant-design/icons';
import { signupApi, persistSignupSession } from '../lib/api/signup';
import { PageHeader } from '../components/framework/PageHeader';

/**
 * /signup 自助注册页（spec 2026-09-04 §self-serve-signup §6）。
 *
 * 三字段：email / password / companyName。
 * 提交成功跳 /settings 引导用户签发首个 API Key（signup 流程本身不发 key，
 * 由用户在 Settings 内调用 /v1/admin/api-keys POST 触发）。
 */
export function Signup() {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [form] = Form.useForm<{ email: string; password: string; companyName: string }>();

  const onSubmit = async () => {
    try {
      const v = await form.validateFields();
      setLoading(true);
      setError('');
      const r = await signupApi.signup(v.email, v.password, v.companyName);
      persistSignupSession(r);
      message.success(`已创建租户 ${r.tenantId}`);
      // 跳 Settings → API Key 管理，引导用户签发首个 key
      setTimeout(() => { window.location.href = '/settings'; }, 400);
    } catch (e) {
      if (e instanceof Error) setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 520 }}>
      <PageHeader
        eyebrow="Sign up"
        title="创建账号"
        sub="30 秒自助开通，立即拥有独立的 Agent Gateway 工作区"
      />
      <Card>
        <Form
          form={form}
          layout="vertical"
          onFinish={onSubmit}
          autoComplete="off"
          requiredMark
        >
          <Form.Item
            label="公司 / 团队名"
            name="companyName"
            rules={[
              { required: true, message: '请输入公司或团队名' },
              { max: 64, message: '不超过 64 字符' },
            ]}
          >
            <Input
              prefix={<ShopOutlined />}
              placeholder="Acme Co."
              size="large"
              data-testid="signup-company"
            />
          </Form.Item>

          <Form.Item
            label="邮箱"
            name="email"
            rules={[
              { required: true, message: '请输入邮箱' },
              { type: 'email', message: '邮箱格式不合法' },
            ]}
          >
            <Input
              prefix={<MailOutlined />}
              placeholder="you@example.com"
              size="large"
              autoComplete="email"
              data-testid="signup-email"
            />
          </Form.Item>

          <Form.Item
            label="密码"
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 8, message: '密码至少 8 位' },
            ]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="至少 8 位"
              size="large"
              autoComplete="new-password"
              data-testid="signup-password"
            />
          </Form.Item>

          {error && (
            <Alert
              type="error"
              showIcon
              message={error}
              style={{ marginBottom: 16 }}
              data-testid="signup-error"
            />
          )}

          <Form.Item style={{ marginBottom: 0 }}>
            <Button
              type="primary"
              htmlType="submit"
              size="large"
              block
              loading={loading}
              data-testid="signup-submit"
            >
              创建账号
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Typography.Paragraph type="secondary" style={{ textAlign: 'center', margin: 0 }}>
        已有账号？
        <a href="/login" style={{ marginLeft: 6 }}>Admin 登录</a>
        <span style={{ margin: '0 6px' }}>·</span>
        <a href="/demo" style={{ marginLeft: 6 }}>先试用 Demo</a>
      </Typography.Paragraph>
    </Space>
  );
}