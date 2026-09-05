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
    blurb: '自助 GitHub Discussions + 文档。',
    cta: { label: '去 GitHub Discussions', to: 'https://github.com/tlbfpx/agent-gateway/discussions' },
  },
  {
    name: 'Team',
    color: 'blue',
    blurb: '工单 24h 响应 + Slack 共享频道（30 天）。',
    cta: { label: '升级到 Team', to: '/pricing' },
  },
  {
    name: 'Enterprise',
    color: 'gold',
    blurb: '7×24h 响应 + 专属 Slack + 季度架构评审。',
    cta: { label: '联系销售', to: 'mailto:sales@agent-gateway.local' },
  },
];

/**
 * /contact — 销售渠道索引（spec 2026-09-05 §support）。
 *
 *  把客户想问的问题路由到正确邮箱，避免「sales@ 收到 PGP key 请求」这种低效沟通。
 */
export function Contact() {
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Contact"
        title="联系我们"
        sub="按问题类型选通道 — 7×24h 响应，5 工作日 SLA"
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

      <Card title="按版本选支持通道">
        <Row gutter={[16, 16]}>
          {TIERS.map((t) => (
            <Col xs={24} md={8} key={t.name}>
              <Card size="small">
                <Space direction="vertical" size="small" style={{ width: '100%' }}>
                  <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                    <Text strong>{t.name}</Text>
                    <Tag color={t.color}>{t.color === 'gold' ? '推荐' : t.color}</Tag>
                  </Space>
                  <Paragraph type="secondary" style={{ margin: 0 }}>{t.blurb}</Paragraph>
                  <Link to={t.cta.to}>
                    <Button block>{t.cta.label}</Button>
                  </Link>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>

      <Card size="small">
        <Paragraph type="secondary" style={{ margin: 0 }}>
          💡 内部沟通渠道（Slack / 钉钉 / 飞书）需先建立 NDA。Enterprise 客户签约后 5 个工作日内开通专属频道。
        </Paragraph>
      </Card>
    </Space>
  );
}