import { Card, Space, Tag, Typography, Button, Divider, Row, Col } from 'antd';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';

const { Title, Paragraph, Text } = Typography;

interface Tier {
  key: 'community' | 'team' | 'enterprise';
  name: string;
  price: string;
  period: string;
  blurb: string;
  highlight: boolean;
  cta: { label: string; to: string };
}

const TIERS: Tier[] = [
  {
    key: 'community',
    name: 'Community',
    price: '免费',
    period: '永久',
    blurb: '个人 / 体验用，含全部核心功能。Demo 模式 24h 试用。',
    highlight: false,
    cta: { label: '一键试用 Demo', to: '/demo' },
  },
  {
    key: 'team',
    name: 'Team',
    price: '¥299',
    period: '/ 月',
    blurb: '小团队协作，含 SSO + 审计 CSV + Helm 自托管镜像。',
    highlight: true,
    cta: { label: '自助注册', to: '/signup' },
  },
  {
    key: 'enterprise',
    name: 'Enterprise',
    price: '面议',
    period: '按部署',
    blurb: '专属 OIDC 接入 + SLA 99.95% + 私有化部署 + 7×24 支持。',
    highlight: false,
    cta: { label: '联系我们', to: 'mailto:sales@agent-gateway.local' },
  },
];

interface FeatureRow {
  group: string;
  feature: string;
  community: boolean | string;
  team: boolean | string;
  enterprise: boolean | string;
}

const FEATURES: FeatureRow[] = [
  { group: '核心', community: true, team: true, enterprise: true,
    feature: '统一 chat / agents / auth 网关（35+ 页面）' },
  { group: '核心', community: 'Demo 24h', team: '无限', enterprise: '无限',
    feature: 'Demo / 自助注册 / 多租户' },
  { group: '核心', community: '社区支持', team: '工单 24h', enterprise: '7×24',
    feature: '支持响应时间' },

  { group: '安全与合规', community: false, team: true, enterprise: true,
    feature: '审计日志 CSV 导出（SOC2/ISO27001）' },
  { group: '安全与合规', community: false, team: true, enterprise: true,
    feature: '多租户越权防护（TenantEnforcementFilter）' },
  { group: '安全与合规', community: 'Demo 默认开', team: '可关', enterprise: '强制关',
    feature: 'Demo 模式（试用租户）' },

  { group: '企业集成', community: false, team: '5 分钟接入', enterprise: '专属配置',
    feature: 'OIDC SSO（Azure AD / Okta / Auth0 / Google）' },
  { group: '企业集成', community: false, team: true, enterprise: true,
    feature: 'Helm chart（k8s 一键部署 + HPA + Ingress + TLS）' },
  { group: '企业集成', community: false, team: '标准', enterprise: '专属',
    feature: 'JWT RS256 + JWKS 自动轮换' },

  { group: '可观测', community: '/status.json', team: 'Prometheus 抓取', enterprise: 'SLA 99.95%',
    feature: '监控集成' },
  { group: '可观测', community: true, team: true, enterprise: true,
    feature: 'Changelog / 升级路径' },

  { group: '部署', community: '本地 docker', team: '云 SaaS / 自托管', enterprise: '私有化',
    feature: '部署模式' },
  { group: '部署', community: '公开', team: '公开', enterprise: '专属',
    feature: 'API 文档 + OpenAPI SDK' },
];

const FAQ = [
  {
    q: 'Demo 模式数据安全吗？',
    a: 'Demo 模式 24h 自动清理 key 和租户；首次注册用户用 bcrypt 密码 + admin token，本地不会保留密码明文。生产部署 demo.enabled=false 默认关闭。',
  },
  {
    q: 'Self-host 有什么前置？',
    a: 'Kubernetes 1.23+ + Postgres（可用 docker-compose 启动）+一个 OIDC IdP（可选）。Helm chart 一键完成，详情见 deploy/helm/agent-gateway/README.md。',
  },
  {
    q: 'OIDC 接入需要什么？',
    a: '只需 issuer + client-id + client-secret 三个配置项。Discovery 自动发现所有端点。详细 5 分钟步骤见 docs/operators/OIDC.md。',
  },
  {
    q: '怎么从 Team 升级到 Enterprise？',
    a: '联系 sales（按钮在上方），我们会做一次架构评估 + SLA 协商。',
  },
];

function Cell({ v }: { v: boolean | string }) {
  if (typeof v === 'boolean') {
    return v
      ? <CheckOutlined style={{ color: '#52c41a', fontSize: 16 }} />
      : <CloseOutlined style={{ color: '#bfbfbf', fontSize: 16 }} />;
  }
  return <Text style={{ fontSize: 13 }}>{v}</Text>;
}

function GroupHeader({ label }: { label: string }) {
  return (
    <td style={{
      background: 'var(--bg-2, #fafafa)',
      fontWeight: 600,
      padding: '12px 16px',
    }}>
      { label}
    </td>
  );
}

/**
 * /pricing — 定价 + 功能对比 + FAQ（spec 2026-09-05 §pricing）。
 *
 * 销售必备：买家对比三档（Community / Team / Enterprise），
 * 看完能直接点 CTA 进入 /demo 或 /signup。
 */
export function Pricing() {
  const groups = Array.from(new Set(FEATURES.map((f) => f.group)));
  const t = useT();

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Pricing"
        title={t('pricing.title')}
        sub={t('pricing.subtitle')}
      />

      {/* 三档定价卡 */}
      <Row gutter={[16, 16]}>
        {TIERS.map((tier) => (
          <Col xs={24} md={8} key={tier.key}>
            <Card
              data-testid={`pricing-tier-${tier.key}`}
              style={{
                borderColor: tier.highlight ? '#1677ff' : undefined,
                borderWidth: tier.highlight ? 2 : 1,
              }}
            >
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Text strong style={{ fontSize: 18 }}>{tier.name}</Text>
                  {tier.highlight && <Tag color="blue">{t('pricing.recommended')}</Tag>}
                </Space>
                <Space align="baseline">
                  <Text style={{ fontSize: 32, fontWeight: 700 }}>{tier.price}</Text>
                  <Text type="secondary">{tier.period}</Text>
                </Space>
                <Paragraph type="secondary" style={{ margin: 0 }}>{tier.blurb}</Paragraph>
                <Link to={tier.cta.to}>
                  <Button
                    type={tier.highlight ? 'primary' : 'default'}
                    block
                    size="large"
                  >
                    {tier.cta.label}
                  </Button>
                </Link>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 功能矩阵 */}
      <Card title="功能对比">
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border-color, #d9d9d9)' }}>
                <th style={{ textAlign: 'left', padding: '12px 16px' }}>功能</th>
                <th style={{ textAlign: 'center', padding: '12px 16px', width: 120 }}>Community</th>
                <th style={{ textAlign: 'center', padding: '12px 16px', width: 120, color: '#1677ff' }}>Team ⭐</th>
                <th style={{ textAlign: 'center', padding: '12px 16px', width: 120 }}>Enterprise</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={`hdr-${g}`}>
                  <GroupHeader label={g} />
                  <td colSpan={3} style={{ background: 'var(--bg-2, #fafafa)' }} />
                </tr>
              ))}
              {FEATURES.map((f) => (
                <tr
                  key={`f-${f.feature}`}
                  style={{ borderBottom: '1px solid var(--border-color, #f0f0f0)' }}
                >
                  <td style={{ padding: '10px 16px' }}>{f.feature}</td>
                  <td style={{ padding: '10px 16px', textAlign: 'center' }}><Cell v={f.community} /></td>
                  <td style={{ padding: '10px 16px', textAlign: 'center' }}><Cell v={f.team} /></td>
                  <td style={{ padding: '10px 16px', textAlign: 'center' }}><Cell v={f.enterprise} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* FAQ */}
      <Card title="常见问题">
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {FAQ.map((f) => (
            <div key={f.q}>
              <Title level={5} style={{ margin: 0 }}>{f.q}</Title>
              <Paragraph type="secondary" style={{ margin: '4px 0 0 0' }}>{f.a}</Paragraph>
            </div>
          ))}
        </Space>
      </Card>

      <Divider style={{ margin: '8px 0' }} />

      <Space style={{ width: '100%', justifyContent: 'center' }} size="large">
        <Link to="/demo"><Button type="primary" size="large">{t('pricing.tryFirst')}</Button></Link>
        <Link to="/signup"><Button size="large">{t('pricing.signupNow')}</Button></Link>
        <Link to="/changelog"><Button size="large" type="text">{t('pricing.v020Changelog')}</Button></Link>
      </Space>
    </Space>
  );
}