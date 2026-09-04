import { useEffect, useState } from 'react';
import { Alert, Button, Card, Space, Spin, Tag, Typography, message } from 'antd';
import { ThunderboltOutlined, LoginOutlined, GiftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { demoApi, persistDemoSession, type DemoStatus } from '../lib/api/demo';
import { PageHeader } from '../components/framework/PageHeader';
import { PageLoading } from '../components/framework/PageLoading';

const { Title, Paragraph } = Typography;

/**
 * /demo 着陆页（spec 2026-09-04 §demo-mode §6）。
 *
 * 首屏检测：
 * - demo 启用 → 显示「试用 Demo」CTA（自动 bootstrap 一份独立租户）
 * - demo 关闭 / 检测失败 → 降级为「登录」入口
 */
export function Demo() {
  const [status, setStatus] = useState<DemoStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [bootstrapping, setBootstrapping] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const s = await demoApi.status();
        if (!cancelled) setStatus(s);
      } catch {
        if (!cancelled) setStatus({ enabled: false, ttlSeconds: 0 });
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const onTryDemo = async () => {
    setBootstrapping(true);
    try {
      const session = await demoApi.bootstrap();
      persistDemoSession(session);
      message.success('Demo 租户已创建 · 24h 内有效');
      // 跳转到 dashboard；用 window.location 让所有模块重新初始化
      window.location.href = '/dashboard';
    } catch (e) {
      message.error(`Bootstrap 失败：${e instanceof Error ? e.message : String(e)}`);
      setBootstrapping(false);
    }
  };

  if (loading) return <PageLoading description="检测 Demo 模式…" />;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 720 }}>
      <PageHeader
        eyebrow="Welcome"
        title="Agent Gateway"
        sub="AI Agent 调用的统一网关：路由、限流、计费、审计"
      />

      {status?.enabled ? (
        <Card
          data-testid="demo-cta-card"
          style={{
            background: 'linear-gradient(135deg, #f0f5ff 0%, #fff7e6 100%)',
            borderColor: '#faad14',
          }}
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Title level={4} style={{ margin: 0 }}>
              <GiftOutlined style={{ color: '#faad14', marginRight: 8 }} />
              试用 Demo · 无需注册
            </Title>
            <Paragraph>
              点击下方按钮，本系统会为你创建一个独立的 demo 租户（独立数据隔离），
              包含：
            </Paragraph>
            <ul>
              <li>API Key（24h 有效期）</li>
              <li>Admin 账号（OWNER 角色，可访问管理端）</li>
              <li>预置的 echo-agent 和示例模型</li>
            </ul>
            <Paragraph type="secondary" style={{ margin: 0 }}>
              <Tag color="orange">注意</Tag>
              Demo 数据 24 小时后自动清理；正式使用请通过设置页绑定你的真实账号。
            </Paragraph>
            <Button
              type="primary"
              size="large"
              icon={<ThunderboltOutlined />}
              loading={bootstrapping}
              onClick={onTryDemo}
              data-testid="demo-bootstrap-btn"
            >
              {bootstrapping ? '创建中…' : '一键试用 Demo'}
            </Button>
          </Space>
        </Card>
      ) : (
        <Alert
          type="info"
          showIcon
          message="Demo 模式未启用"
          description="请联系管理员开启 GATEWAY_DEMO_ENABLED 环境变量；或直接登录。"
        />
      )}

      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Title level={5} style={{ margin: 0 }}>
            已有账号？
          </Title>
          <Paragraph type="secondary" style={{ margin: 0 }}>
            在「设置」页填入你的 API Key + 租户 ID + Admin Token 即可登录管理端。
          </Paragraph>
          <Space>
            <Button icon={<LoginOutlined />} onClick={() => navigate('/login')}>
              Admin 登录
            </Button>
            <Button onClick={() => navigate('/settings')}>
              设置凭据
            </Button>
          </Space>
        </Space>
      </Card>
    </Space>
  );
}