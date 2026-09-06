/**
 * i18n.ts — 极简中英翻译（spec §i18n）。
 *
 * 用法：
 *   const t = useT();
 *   <Title>{t('pricing.title')}</Title>
 *
 * 优先级：localStorage.agent-gateway.lang > data-lang attr > 'zh'
 * 切换机制：DisplaySwitcher 写 data-lang + localStorage；main.tsx 监听 data-lang
 * 触发 useState 重渲染；这里再读一次保证一致。
 *
 * 设计：内联小型 t()，不引入 i18next/react-intl。40 个营销页用 t() 覆盖就行；
 * 业务页（chat/agents/cost 等）保持中文（antd locale 已切换部分 UI 文字）。
 */
import { useEffect, useState } from 'react';

export type Lang = 'zh' | 'en';

function readLang(): Lang {
  if (typeof window === 'undefined') return 'zh';
  const stored = window.localStorage?.getItem('agent-gateway.lang');
  if (stored === 'en') return 'en';
  if (stored === 'zh') return 'zh';
  const attr = document.documentElement.getAttribute('data-lang');
  if (attr === 'en') return 'en';
  return 'zh';
}

const STRINGS: Record<string, Record<Lang, string>> = {
  // ---- /pricing ----
  'pricing.title':        { zh: '选择适合你的版本', en: 'Choose the plan that fits' },
  'pricing.subtitle':     { zh: '核心功能全版本可用，按规模与合规要求选档', en: 'All plans ship the core. Pick by scale and compliance.' },
  'pricing.community':    { zh: 'Community', en: 'Community' },
  'pricing.team':         { zh: 'Team', en: 'Team' },
  'pricing.enterprise':   { zh: 'Enterprise', en: 'Enterprise' },
  'pricing.recommended':  { zh: '推荐', en: 'Recommended' },
  'pricing.freeForever':  { zh: '永久', en: 'forever' },
  'pricing.perMonth':     { zh: '/ 月', en: '/ mo' },
  'pricing.contact':      { zh: '面议', en: 'Contact us' },
  'pricing.byDeploy':     { zh: '按部署', en: 'per deployment' },
  'pricing.communityBlurb': { zh: '个人 / 体验用，含全部核心功能。Demo 模式 24h 试用。', en: 'For individuals & trial. All core features. 24h Demo mode.' },
  'pricing.teamBlurb':    { zh: '小团队协作，含 SSO + 审计 CSV + Helm 自托管镜像。', en: 'Small teams. SSO + audit CSV + self-hosted Helm image.' },
  'pricing.enterpriseBlurb': { zh: '专属 OIDC 接入 + SLA 99.95% + 私有化部署 + 7×24 支持。', en: 'Dedicated OIDC + 99.95% SLA + on-prem + 24/7 support.' },
  'pricing.tryDemo':      { zh: '一键试用 Demo', en: 'Try the Demo' },
  'pricing.signup':       { zh: '自助注册', en: 'Sign up' },
  'pricing.contactSales': { zh: '联系我们', en: 'Contact sales' },
  'pricing.matrixTitle':  { zh: '功能对比', en: 'Feature comparison' },
  'pricing.faq':          { zh: '常见问题', en: 'FAQ' },
  'pricing.tryFirst':     { zh: '先试用 Demo', en: 'Try Demo first' },
  'pricing.signupNow':    { zh: '直接注册', en: 'Sign up' },
  'pricing.v020Changelog':{ zh: 'v0.2.0 更新日志', en: 'v0.2.0 changelog' },

  // ---- /contact ----
  'contact.title':        { zh: '联系我们', en: 'Contact us' },
  'contact.subtitle':     { zh: '按问题类型选通道 — 7×24h 响应，5 工作日 SLA', en: 'Pick the right channel. 24/7 response, 5-day SLA.' },
  'contact.sales':        { zh: '销售咨询', en: 'Sales' },
  'contact.salesDesc':    { zh: 'Team / Enterprise 报价、定制 SLA、私有化部署评估、采购流程对接。', en: 'Team/Enterprise quotes, custom SLA, on-prem eval, procurement.' },
  'contact.security':     { zh: '漏洞披露', en: 'Security disclosure' },
  'contact.securityDesc': { zh: 'PGP key 与披露流程见 SECURITY.md。我们承诺 7 个工作日响应、严重漏洞 24h 修复。', en: 'PGP key and disclosure process in SECURITY.md. 7-day response, 24h for critical.' },
  'contact.privacy':     { zh: '隐私 / 合规', en: 'Privacy / compliance' },
  'contact.privacyDesc': { zh: '数据请求、GDPR / CCPA 删除、审计日志导出、Sub-processor 列表。', en: 'Data requests, GDPR/CCPA deletion, audit export, sub-processor list.' },
  'contact.docs':         { zh: '技术文档', en: 'Tech docs' },
  'contact.docsDesc':     { zh: 'API 参考、OIDC 接入、Helm 部署、SDK 示例。docs/operators/OIDC.md 包含 4 大 IdP 接入指南。', en: 'API reference, OIDC setup, Helm deploy, SDK samples. docs/operators/OIDC.md has 4 IdP guides.' },
  'contact.community':    { zh: '社区', en: 'Community' },
  'contact.communityDesc':{ zh: '用例分享、feature request、最佳实践。GitHub Discussions 公开归档。', en: 'Use cases, feature requests, best practices. Public GitHub Discussions.' },
  'contact.tierSupport':  { zh: '按版本选支持通道', en: 'Support by plan' },
  'contact.internalHint':{ zh: '内部沟通渠道（Slack / 钉钉 / 飞书）需先建立 NDA。Enterprise 客户签约后 5 个工作日内开通专属频道。', en: 'Internal channels (Slack/DingTalk/Feishu) require NDA. Enterprise clients get dedicated channel within 5 business days.' },
};

/** 静态翻译查找（hook 外可用）。 */
export function tStatic(key: string, lang?: Lang): string {
  const l = lang ?? readLang();
  const entry = STRINGS[key];
  if (!entry) return key;
  return entry[l] ?? entry.zh ?? key;
}

/** React hook：跟随 data-lang / localStorage 变化重渲染。 */
export function useT() {
  const [lang, setLang] = useState<Lang>(readLang);
  useEffect(() => {
    const obs = new MutationObserver(() => setLang(readLang()));
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-lang'] });
    const onStorage = () => setLang(readLang());
    window.addEventListener('storage', onStorage);
    return () => {
      obs.disconnect();
      window.removeEventListener('storage', onStorage);
    };
  }, []);
  return (key: string) => tStatic(key, lang);
}
