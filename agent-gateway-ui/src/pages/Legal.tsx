import { Card, Space, Typography } from 'antd';
import { PageHeader } from '../components/framework/PageHeader';

const { Title, Paragraph, Text } = Typography;

interface LegalProps {
  kind: 'terms' | 'privacy';
}

/**
 * /legal/terms & /legal/privacy — B2B 合规占位（spec §legal）。
 *
 * <p>Apache-2.0 开源 + SaaS 服务条款 = 大多数买家会要的最低配置。
 * 这里是真实可用但占位内容：建议联系法务替换（每个公司不同）。
 */
export function Terms({ kind }: LegalProps) {
  if (kind === 'terms') return <TermsBody />;
  return <PrivacyBody />;
}

function TermsBody() {
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Legal · 服务条款"
        title="Terms of Service"
        sub={`最后更新：2026-09-05 · v0.2.0`}
      />
      <Card>
        <Space direction="vertical" size="middle" style={{ lineHeight: 1.8 }}>
          <section><Title level={3}>1. 服务范围</Title>
            <Paragraph>
              Agent Gateway（以下简称"网关"）是一款企业级 AI Agent 网关软件。
              <Text strong>开源版本</Text>采用 Apache-2.0 协议，源码可自由使用、修改、分发，
              <Text strong>无任何保证</Text>。
            </Paragraph>
            <Paragraph>
              <Text strong>商业服务</Text>（Team / Enterprise）由网关运营方提供 SLA 支持，
             具体条款以双方签订的商业合同为准。
            </Paragraph>
          </section>

          <section><Title level={3}>2. 数据所有权</Title>
            <Paragraph>
              您的租户数据（包括但不限于：API Key、调用日志、审计记录、计费数据）归您所有。
              网关运营方不在您的书面授权下访问、修改或披露您的数据（除非法律法规强制要求）。
            </Paragraph>
            <Paragraph>
              卸载时：自助删除 Postgres 表 + 清空 <code>data/</code> 目录即可；如有需要可联系
              运营方协助（Enterprise SLA 内）。
            </Paragraph>
          </section>

          <section><Title level={3}>3. Demo 模式</Title>
            <Paragraph>
              Demo 模式（<code>/demo</code>）创建临时试用租户，<Text strong>24 小时</Text>自动清理 API Key 和租户数据。
              不应用于生产或存放真实业务数据。
            </Paragraph>
          </section>

          <section><Title level={3}>4. 安全披露</Title>
            <Paragraph>
              漏洞报告：<a href="mailto:security@agent-gateway.local">security@agent-gateway.local</a>。
              我们承诺 7 个工作日内响应，严重漏洞 24 小时内修复。
            </Paragraph>
          </section>

          <section><Title level={3}>5. 免责声明</Title>
            <Paragraph>
              开源版本按 Apache-2.0 协议分发。<Text strong>无任何明示或暗示的保证</Text>，
              包括但不限于适销性、特定用途适用性、不侵权性。
              使用风险由您自行承担。
            </Paragraph>
          </section>
        </Space>
      </Card>
    </Space>
  );
}

function PrivacyBody() {
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow="Legal · 隐私政策"
        title="Privacy Policy"
        sub={`最后更新：2026-09-05 · v0.2.0`}
      />
      <Card>
        <Space direction="vertical" size="middle" style={{ lineHeight: 1.8 }}>
          <section><Title level={3}>1. 我们收集的数据</Title>
            <Paragraph>
              <Text strong>您提供的数据</Text>：邮箱、租户名（自助注册）、OIDC 元数据
              （仅在你启用企业 SSO 时）。
            </Paragraph>
            <Paragraph>
              <Text strong>服务运行时数据</Text>：API Key 哈希（PBKDF2-HMAC-SHA256 + 16 字节 salt + 32 字节 hash +
              100k 迭代）、调用日志（含时间戳 / 模型 / token 用量 / HTTP 状态码）、审计事件。
            </Paragraph>
            <Paragraph>
              <Text strong>不收集</Text>：聊天内容（除非您主动开启 trace 录制）、请求/响应正文（除非
              显式配置 body logging）、用户行为分析（无第三方 analytics）。
            </Paragraph>
          </section>

          <section><Title level={3}>2. 数据存储与传输</Title>
            <Paragraph>
              数据存储：PostgreSQL（TimescaleDB extension，可选）、<code>data/*.json</code> 文件。
              传输加密：TLS 1.2+（Ingress/Helm chart 默认 cert-manager 自动签证书）。
            </Paragraph>
          </section>

          <section><Title level={3}>3. 数据使用</Title>
            <Paragraph>
              网关运营方 <Text strong>不会</Text>：
            </Paragraph>
            <Paragraph>
              - 用您的数据训练任何 AI 模型<br />
              - 与第三方共享您的数据<br />
              - 用您的行为做产品分析（除非您启用可选的 PostHog 集成）
            </Paragraph>
          </section>

          <section><Title level={3}>4. 您的权利</Title>
            <Paragraph>
              您随时可以：导出全部租户数据（PG dump + <code>data/*.json</code>）；
              删除租户（删 PG 表 + 清 <code>data/</code> 目录）；
              撤回 OIDC token（浏览器登出 IdP 会话）；撤回 API Key。
            </Paragraph>
          </section>

          <section><Title level={3}>5. 联系</Title>
            <Paragraph>
              数据相关问题：<a href="mailto:privacy@agent-gateway.local">privacy@agent-gateway.local</a>。
            </Paragraph>
          </section>
        </Space>
      </Card>
    </Space>
  );
}