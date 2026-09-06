import { Card, Space, Typography } from 'antd';
import { PageHeader } from '../components/framework/PageHeader';
import { useT } from '../lib/i18n';

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

function langOf(): 'zh' | 'en' {
  if (typeof window === 'undefined') return 'zh';
  return (window.localStorage?.getItem('agent-gateway.lang') as 'zh' | 'en' | null) ?? 'zh';
}

function TermsBody() {
  const t = useT();
  const lang = langOf();
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow={`Legal · ${t('terms.title')}`}
        title="Terms of Service"
        sub={`${t('terms.lastUpd')}: 2026-09-05 · v0.2.0`}
      />
      <Card>
        <Space direction="vertical" size="middle" style={{ lineHeight: 1.8 }}>
          <section><Title level={3}>{t('terms.s1.title')}</Title>
            <Paragraph>
              {t('terms.s1.p1')} <Text strong>{t('terms.s1.p1.os')}</Text> {t('terms.s1.p1.bsl')}
              <Text strong>{t('terms.s1.p1.war')}</Text>{lang === 'zh' ? '。' : '.'}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('terms.s1.p2')}</Text>{t('terms.s1.p2.body')}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('terms.s2.title')}</Title>
            <Paragraph>
              {t('terms.s2.p1')}
            </Paragraph>
            <Paragraph>
              {t('terms.s2.p2')}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('terms.s3.title')}</Title>
            <Paragraph>
              {lang === 'zh' ? (
                <>Demo 模式（<code>/demo</code>）创建临时试用租户，<Text strong>24 小时</Text>自动清理 API Key 和租户数据。不应用于生产或存放真实业务数据。</>
              ) : (
                <>Demo mode (<code>/demo</code>) creates a temporary trial tenant; API keys and tenant data are auto-cleaned after <Text strong>24 hours</Text>. Do not use it for production or real business data.</>
              )}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('terms.s4.title')}</Title>
            <Paragraph>
              {lang === 'zh' ? (
                <>漏洞报告：<a href="mailto:security@agent-gateway.local">security@agent-gateway.local</a>。我们承诺 7 个工作日内响应，严重漏洞 24 小时内修复。</>
              ) : (
                <>Report vulnerabilities to <a href="mailto:security@agent-gateway.local">security@agent-gateway.local</a>. We commit to a 7-business-day response; critical issues patched within 24 hours.</>
              )}
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
  const t = useT();
  const lang = langOf();
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <PageHeader
        eyebrow={`Legal · ${t('privacy.title')}`}
        title="Privacy Policy"
        sub={`${t('terms.lastUpd')}: 2026-09-05 · v0.2.0`}
      />
      <Card>
        <Space direction="vertical" size="middle" style={{ lineHeight: 1.8 }}>
          <section><Title level={3}>{t('privacy.s1.title')}</Title>
            <Paragraph>
              <Text strong>{t('privacy.s1.sub1')}</Text>{t('privacy.s1.sub1.body')}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('privacy.s1.sub2')}</Text>{t('privacy.s1.sub2.body')}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('privacy.s1.sub3')}</Text>{t('privacy.s1.sub3.body')}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('privacy.s2.title')}</Title>
            <Paragraph>
              {t('privacy.s2.p1')}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('privacy.s3.title')}</Title>
            <Paragraph>{t('privacy.s3.intro')}</Paragraph>
            <Paragraph>
              {lang === 'zh' ? (
                <>- {t('privacy.s3.b1')}<br />- {t('privacy.s3.b2')}<br />- {t('privacy.s3.b3')}</>
              ) : (
                <>- {t('privacy.s3.b1')},<br />- {t('privacy.s3.b2')},<br />- {t('privacy.s3.b3')}.</>
              )}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('privacy.s4.title')}</Title>
            <Paragraph>
              {lang === 'zh' ? (
                <>您随时可以：导出全部租户数据（PG dump + <code>data/*.json</code>）；删除租户（删 PG 表 + 清 <code>data/</code> 目录）；撤回 OIDC token（浏览器登出 IdP 会话）；撤回 API Key。</>
              ) : (
                <>You can at any time: export all tenant data (pg_dump + <code>data/*.json</code>); delete the tenant (drop tables + clear <code>data/</code>); revoke the OIDC token (sign out of the IdP); revoke API keys.</>
              )}
            </Paragraph>
          </section>

          <section><Title level={3}>{t('privacy.s5.title')}</Title>
            <Paragraph>
              {lang === 'zh' ? (
                <>数据相关问题：<a href="mailto:privacy@agent-gateway.local">privacy@agent-gateway.local</a>。</>
              ) : (
                <>Data-related inquiries: <a href="mailto:privacy@agent-gateway.local">privacy@agent-gateway.local</a>.</>
              )}
            </Paragraph>
          </section>
        </Space>
      </Card>
    </Space>
  );
}