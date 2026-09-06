import { Space, Typography, Button, Card, Row, Col, Tag } from 'antd';
import {
  RocketOutlined,
  GiftOutlined,
  DollarOutlined,
  ApiOutlined,
  SafetyCertificateOutlined,
  GlobalOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';

const { Title, Paragraph } = Typography;

const FEATURES = [
  { key: 'chat',    title: 'AI 聊天 / Agents',         en: 'AI Chat / Agents',  icon: <RocketOutlined />,  desc: '统一 chat / SSE 流式 / 多轮上下文 / 工具调用可视化', enDesc: 'Unified chat, SSE streaming, multi-turn, tool-call visualization' },
  { key: 'rbac',    title: 'Agent / Skill 级 RBAC',    en: 'Agent / Skill RBAC',  icon: <SafetyCertificateOutlined />,  desc: '多租户越权防护 + Skill 细粒度权限', enDesc: 'Multi-tenant isolation + per-skill permission' },
  { key: 'sso',     title: 'OIDC 单租户 + 多租户 SaaS', en: 'OIDC single + multi-tenant',  icon: <GlobalOutlined />,  desc: 'Azure AD / Okta / Auth0 / Google，5 分钟接入', enDesc: 'Azure AD / Okta / Auth0 / Google. 5-min setup' },
  { key: 'audit',   title: '审计日志 + SOC2 CSV 导出', en: 'Audit log + SOC2 CSV',  icon: <ApiOutlined />,  desc: 'append-only + CSV 导出 + Helm 自托管', enDesc: 'append-only + CSV export + self-hosted Helm' },
];

export function Welcome() {
  const t = useT();
  const lang = (typeof window !== 'undefined'
    && window.localStorage?.getItem('agent-gateway.lang') as 'zh' | 'en' | null) ?? 'zh';

  const tr = (zh: string, en: string) => t(lang === 'en' ? en : zh) as unknown as string;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {/* Hero */}
      <Card style={{ background: 'linear-gradient(135deg, #f0f5ff 0%, #fff7e6 100%)' }}>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Title level={2} style={{ margin: 0 }}>
            {tr('AI Agent 调用的统一网关', 'Unified gateway for AI Agent calls')}
          </Title>
          <Paragraph style={{ fontSize: 16, margin: 0 }}>
            {tr(
              '路由、限流、计费、审计、RBAC、缓存。Demo 现成 · 自助注册 30 秒 · OIDC SSO 5 分钟接入。',
              'Routing, rate limits, billing, audit, RBAC, caching. Try Demo · self-serve signup in 30s · OIDC SSO in 5 min.'
            )}
          </Paragraph>
          <Space size="middle" wrap>
            <Link to="/demo">
              <Button type="primary" size="large" icon={<GiftOutlined />}>
                {tr('一键试用 Demo', 'Try the Demo')}
              </Button>
            </Link>
            <Link to="/signup">
              <Button size="large">{tr('自助注册', 'Sign up')}</Button>
            </Link>
            <Link to="/pricing">
              <Button size="large" icon={<DollarOutlined />}>
                {tr('查看定价', 'View pricing')}
              </Button>
            </Link>
          </Space>
          <Space size="small" wrap>
            <Tag color="blue">v0.3.0</Tag>
            <Tag color="green">Apache-2.0</Tag>
            <Tag>{tr('41 个管理页面', '41 admin pages')}</Tag>
            <Tag>{tr('30+ 单测', '30+ tests')}</Tag>
            <Tag color="purple">TS 0 错</Tag>
          </Space>
        </Space>
      </Card>

      {/* Features */}
      <Card title={tr('核心能力', 'Core features')}>
        <Row gutter={[16, 16]}>
          {FEATURES.map((f) => (
            <Col xs={24} md={12} key={f.key}>
              <Card size="small">
                <Space>
                  {f.icon}
                  <strong>{lang === 'en' ? f.en : f.title}</strong>
                </Space>
                <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                  {lang === 'en' ? f.enDesc : f.desc}
                </Paragraph>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      {/* Trust badges */}
      <Card size="small">
        <Space size="large" wrap>
          <span><Tag color="blue">SOC2</Tag>{tr('审计导出', 'Audit export')}</span>
          <span><Tag color="green">TLS 1.2+</Tag>{tr('传输加密', 'Transport encryption')}</span>
          <span><Tag color="purple">RBAC</Tag>{tr('Agent / Skill 细粒度', 'Per-Agent / Per-Skill')}</span>
          <span><Tag color="orange">OIDC</Tag>{tr('多租户 SaaS', 'Multi-tenant SaaS')}</span>
        </Space>
      </Card>

      {/* Final CTA */}
      <Card>
        <Space direction="vertical" size="middle" style={{ width: '100%', textAlign: 'center' }}>
          <Title level={4} style={{ margin: 0 }}>
            {tr('30 秒开通租户，10 分钟上手全部能力', '30s to sign up, 10 min to learn everything')}
          </Title>
          <Space size="middle" wrap style={{ justifyContent: 'center' }}>
            <Link to="/demo">
              <Button type="primary" size="large" icon={<RocketOutlined />}>
                {tr('试用 Demo', 'Try Demo')}
              </Button>
            </Link>
            <Link to="/signup">
              <Button size="large">{tr('自助注册', 'Sign up')}</Button>
            </Link>
            <Link to="/contact">
              <Button size="large" type="text">
                {tr('联系销售', 'Contact sales')}
              </Button>
            </Link>
          </Space>
        </Space>
      </Card>
    </Space>
  );
}