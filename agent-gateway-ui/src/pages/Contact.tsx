import { Card, Space, Tag, Typography, Button, Row, Col } from 'antd';
import {
  MailOutlined,
  BugOutlined,
  SafetyOutlined,
  BookOutlined,
  MessageOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';

const { Title, Paragraph, Text } = Typography;

interface Channel {
  icon: React.ReactNode;
  title: string;
  email: string;
  response: string;
  description: string;
  tagColor: string;
}

const CHANNELS: Channel[] = [
  {
    icon: <MailOutlined style={{ color: '#1677ff', fontSize: 24 }} />,
    title: '销售咨询',
    email: 'sales@agent-gateway.local',
    response: '1 个工作日',
    description: 'Team / Enterprise 报价、定制 SLA、私有化部署评估、采购流程对接。',
    tagColor: 'blue',
  },
  {
    icon: <BugOutlined style={{ color: '#ff4d4f', fontSize: 24 }} />,
    title: '漏洞披露',
    email: 'security@agent-gateway.local',
    response: '24 小时',
    description: 'PGP key 与披露流程见 SECURITY.md（计划中）。我们承诺 7 个工作日响应、严重漏洞 24h 修复。',
    tagColor: 'red',
  },
  {
    icon: <SafetyOutlined style={{ color: '#faad14', fontSize: 24 }} />,
    title: '隐私 / 合规',
    email: 'privacy@agent-gateway.local',
    response: '5 个工作日',
    description: '数据请求、GDPR / CCPA 删除、审计日志导出、Sub-processor 列表。',
    tagColor: 'gold',
  },
  {
    icon: <BookOutlined style={{ color: '#52c41a', fontSize: 24 }} />,
    title: '技术文档',
    email: 'docs@agent-gateway.local',
    response: 'GitHub issue',
    description: 'API 参考、OIDC 接入、Helm 部署、SDK 示例。docs/operators/OIDC.md 包含 4 大 IdP 接入指南。',
    tagColor: 'green',
  },
  {
    icon: <MessageOutlined style={{ color: '#722ed1', fontSize: 24 }} />,
    title: '社区',
    email: 'GitHub Discussions',
    response: '异步',
    description: '用例分享、feature request、最佳实践。GitHub Discussions 公开归档。',
    tagColor: 'purple',
  },
];

const TIERS = [
  {
    name: 'Community',
    color: 'default',
    blurb: { zh: '自助 GitHub Discussions + 文档。', en: 'Self-serve GitHub Discussions + docs.' },
    cta: { zh: '去 GitHub Discussions', en: 'GitHub Discussions', to: 'https://github.com/tlbfpx/agent-gateway/discussions' },
  },
  {
    name: 'Team',
    color: 'blue',
    blurb: { zh: '工单 24h 响应 + Slack 共享频道（30 天）。', en: 'Ticket 24h response + shared Slack (30 days).' },
    cta: { zh: '升级到 Team', en: 'Upgrade to Team', to: '/pricing' },
  },
  {
    name: 'Enterprise',
    color: 'gold',
    blurb: { zh: '7×24h 响应 + 专属 Slack + 季度架构评审。', en: '24/7 response + dedicated Slack + quarterly review.' },
    cta: { zh: '联系销售', en: 'Contact sales', to: 'mailto:sales@agent-gateway.local' },
  },
];

/**
 * /contact — 销售渠道索引（spec 2026-09-05 §support）。
 *
 *  把客户想问的问题路由到正确邮箱，避免「sales@ 收到 PGP key 请求」这种低效沟通。
 */
export function Contact() {
  const t = useT();
  const lang = (typeof window !== 'undefined'
    && window.localStorage?.getItem('agent-gateway.lang')) as 'zh' | 'en' | null ?? 'zh';

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Contact"
        title={t('contact.title')}
        sub={t('contact.subtitle')}
      />

      <Row gutter={[16, 16]}>
        {CHANNELS.map((c) => (
          <Col xs={24} md={12} lg={8} key={c.email}>
            <Card data-testid={`contact-channel-${c.title}`}>
              <Space direction="vertical" size="small" style={{ width: '100%' }}>
                <Space>
                  {c.icon}
                  <Text strong style={{ fontSize: 16 }}>{c.title}</Text>
                </Space>
                <Paragraph type="secondary" style={{ margin: 0, minHeight: 48 }}>
                  {c.description}
                </Paragraph>
                <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
                  <a href={`mailto:${c.email}`}>
                    <Tag color={c.tagColor} icon={<MailOutlined />}>{c.email}</Tag>
                  </a>
                  <Text type="secondary" style={{ fontSize: 12 }}>{c.response}</Text>
                </Space>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Card title={t('contact.tierSupport')}>
        <Row gutter={[16, 16]}>
          {TIERS.map((t2) => (
            <Col xs={24} md={8} key={t2.name}>
              <Card size="small">
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                    <Text strong>{t2.name}</Text>
                    <Tag color={t2.color}>{t2.color === 'gold' ? (lang === 'en' ? 'Recommended' : '推荐') : t2.color}</Tag>
                  </Space>
                  <Paragraph type="secondary" style={{ margin: 0 }}>{t2.blurb[lang]}</Paragraph>
                  <Link to={t2.cta.to}>
                    <Button block>{t2.cta[lang]}</Button>
                  </Link>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      <Card size="small">
        <Paragraph type="secondary" style={{ margin: 0 }}>
          {t('contact.internalHint')}
        </Paragraph>
      </Card>
    </Space>
  );
}