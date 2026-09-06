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

  // ---- /getting-started ----
  'gs.title':       { zh: '快速上手', en: 'Get started' },
  'gs.subtitle':    { zh: '按这 5 步走完，10 分钟解锁全部能力', en: '5 steps · 10 minutes · all features unlocked' },
  'gs.complete':    { zh: '全部完成', en: 'All done!' },
  'gs.step.signup':     { zh: '注册账号', en: 'Sign up' },
  'gs.step.signupDesc': { zh: '邮箱 + 公司名 + 密码（≥8 位）。10 秒开通独立租户。', en: 'Email + company + password (8+). 10 seconds, your own tenant.' },
  'gs.step.createKey':  { zh: '签发首把 API Key', en: 'Create your first API Key' },
  'gs.step.createKeyDesc': { zh: '进入「设置」一键签发。所有功能都靠 Key 调用（chat/feedback/metrics/cache）。', en: 'Settings → one click. Every feature needs a Key.' },
  'gs.step.tryChat':    { zh: '试一次对话', en: 'Try a chat' },
  'gs.step.tryChatDesc': { zh: '进入「对话」页，选 echo-agent（演示用），发送「你好」。能看到完整 SSE 流式响应。', en: 'Chat → echo-agent → \"hi\". Full SSE response.' },
  'gs.step.audit':      { zh: '查看审计日志', en: 'Check audit log' },
  'gs.step.auditDesc':  { zh: '刚才的 chat 调用会自动记录到「审计」页。SOC2 合规：右上角可导出 CSV。', en: 'Your chat auto-logs to Audit. SOC2: export CSV top-right.' },
  'gs.step.sso':       { zh: '（可选）接入企业 SSO', en: '(optional) Enterprise SSO' },
  'gs.step.ssoDesc':   { zh: 'Azure AD / Okta / Auth0 / Google 全部支持。5 分钟接入指南在 docs/operators/OIDC.md。', en: 'Azure AD / Okta / Auth0 / Google. 5-min guide in docs/operators/OIDC.md.' },
  'gs.action.go':      { zh: '去操作', en: 'Open' },
  'gs.refreshHint':    { zh: '需要更多帮助？', en: 'Need more help?' },

  // ---- /changelog ----
  'cl.title':      { zh: '产品变更日志', en: 'Product changelog' },
  'cl.subtitle':   { zh: '关注进度与升级路径', en: 'Track progress & upgrade path' },
  'cl.empty':      { zh: '暂无可显示的 release', en: 'No releases to show' },
  'cl.upgradePath':{ zh: '升级路径：每个 bullet 都是独立 atomic commit，按需 revert。', en: 'Upgrade: each bullet is an atomic commit, revert as needed.' },

  // ---- /legal/terms ----
  'terms.title':     { zh: '服务条款', en: 'Terms of Service' },
  'terms.lastUpd':  { zh: '最后更新', en: 'Last updated' },
  'terms.s1.title': { zh: '1. 服务范围', en: '1. Service scope' },
  'terms.s1.p1':    { zh: 'Agent Gateway（以下简称"网关"）是一款企业级 AI Agent 网关软件。', en: 'Agent Gateway (the "Gateway") is enterprise AI Agent gateway software.' },
  'terms.s1.p1.os': { zh: '开源版本', en: 'The open-source edition' },
  'terms.s1.p1.bsl':{ zh: '采用 Apache-2.0 协议，源码可自由使用、修改、分发，', en: 'is licensed under Apache-2.0; you may use, modify, and redistribute the source freely,' },
  'terms.s1.p1.war':{ zh: '无任何保证', en: 'with NO WARRANTY of any kind' },
  'terms.s1.p2':    { zh: '商业服务', en: 'The commercial offering' },
  'terms.s1.p2.body': { zh: '（Team / Enterprise）由网关运营方提供 SLA 支持，具体条款以双方签订的商业合同为准。',
                          en: '(Team / Enterprise) is delivered with SLA support by the Gateway operator; specific terms are governed by the signed commercial agreement.' },
  'terms.s2.title': { zh: '2. 数据所有权', en: '2. Data ownership' },
  'terms.s2.p1':    { zh: '您的租户数据（包括但不限于：API Key、调用日志、审计记录、计费数据）归您所有。网关运营方不在您的书面授权下访问、修改或披露您的数据（除非法律法规强制要求）。',
                       en: 'Your tenant data (including but not limited to: API Keys, call logs, audit records, billing data) belongs to you. The Gateway operator will not access, modify, or disclose it without your written authorization, unless required by law.' },
  'terms.s2.p2':    { zh: '卸载时：自助删除 Postgres 表 + 清空 data/ 目录即可；如有需要可联系运营方协助（Enterprise SLA 内）。',
                       en: 'To uninstall: drop the Postgres tables and clear data/. Enterprise customers can ask the operator for help (covered by SLA).' },
  'terms.s3.title': { zh: '3. Demo 模式', en: '3. Demo mode' },
  'terms.s3.p1':    { zh: 'Demo 模式（/demo）创建临时试用租户，24 小时自动清理 API Key 和租户数据。不应用于生产或存放真实业务数据。',
                       en: 'Demo mode (/demo) creates a temporary trial tenant; API keys and tenant data are auto-cleaned after 24 hours. Do not use it for production or real business data.' },
  'terms.s4.title': { zh: '4. 安全披露', en: '4. Security disclosure' },
  'terms.s4.p1':    { zh: '漏洞报告：security@agent-gateway.local。我们承诺 7 个工作日内响应，严重漏洞 24 小时内修复。',
                       en: 'Report vulnerabilities to security@agent-gateway.local. We commit to a 7-business-day response; critical issues patched within 24 hours.' },
  'terms.s5.title': { zh: '5. 免责声明', en: '5. Disclaimer' },
  'terms.s5.p1':    { zh: '开源版本按 Apache-2.0 协议分发。无任何明示或暗示的保证，包括但不限于适销性、特定用途适用性、不侵权性。使用风险由您自行承担。',
                       en: 'The open-source edition is distributed under Apache-2.0, with NO WARRANTY of any kind, express or implied, including but not limited to merchantability, fitness for a particular purpose, and non-infringement. Use at your own risk.' },

  // ---- /legal/privacy ----
  'privacy.title':     { zh: '隐私政策', en: 'Privacy Policy' },
  'privacy.s1.title':  { zh: '1. 我们收集的数据', en: '1. Data we collect' },
  'privacy.s1.sub1':   { zh: '您提供的数据', en: 'Data you provide' },
  'privacy.s1.sub1.body': { zh: '：邮箱、租户名（自助注册）、OIDC 元数据（仅在你启用企业 SSO 时）。',
                              en: ': email, tenant name (self-serve signup), OIDC metadata (only when you enable Enterprise SSO).' },
  'privacy.s1.sub2':   { zh: '服务运行时数据', en: 'Runtime data' },
  'privacy.s1.sub2.body': { zh: '：API Key 哈希（PBKDF2-HMAC-SHA256 + 16 字节 salt + 32 字节 hash + 100k 迭代）、调用日志（含时间戳 / 模型 / token 用量 / HTTP 状态码）、审计事件。',
                              en: ': API Key hash (PBKDF2-HMAC-SHA256 + 16-byte salt + 32-byte hash + 100k iterations), call logs (timestamp / model / token usage / HTTP status), audit events.' },
  'privacy.s1.sub3':   { zh: '不收集', en: 'We do NOT collect' },
  'privacy.s1.sub3.body': { zh: '：聊天内容（除非您主动开启 trace 录制）、请求/响应正文（除非显式配置 body logging）、用户行为分析（无第三方 analytics）。',
                              en: ': chat content (unless you enable trace recording), request/response bodies (unless body logging is configured), user behavior analytics (no third-party trackers).' },
  'privacy.s2.title':  { zh: '2. 数据存储与传输', en: '2. Storage and transport' },
  'privacy.s2.p1':     { zh: '数据存储：PostgreSQL（TimescaleDB extension，可选）、data/*.json 文件。传输加密：TLS 1.2+（Ingress/Helm chart 默认 cert-manager 自动签证书）。',
                            en: 'Storage: PostgreSQL (optional TimescaleDB extension) and data/*.json. Transport: TLS 1.2+ (Ingress/Helm default to cert-manager auto-signed certs).' },
  'privacy.s3.title':  { zh: '3. 数据使用', en: '3. How we use your data' },
  'privacy.s3.intro':  { zh: '网关运营方 不会：', en: 'The Gateway operator will NOT:' },
  'privacy.s3.b1':     { zh: '用您的数据训练任何 AI 模型', en: 'use your data to train any AI model,' },
  'privacy.s3.b2':     { zh: '与第三方共享您的数据', en: 'share your data with third parties,' },
  'privacy.s3.b3':     { zh: '用您的行为做产品分析（除非您启用可选的 PostHog 集成）', en: 'use your behavior for product analytics (unless you opt-in to PostHog).' },
  'privacy.s4.title':  { zh: '4. 您的权利', en: '4. Your rights' },
  'privacy.s4.p1':     { zh: '您随时可以：导出全部租户数据（PG dump + data/*.json）；删除租户（删 PG 表 + 清 data/ 目录）；撤回 OIDC token（浏览器登出 IdP 会话）；撤回 API Key。',
                            en: 'You can at any time: export all tenant data (pg_dump + data/*.json); delete the tenant (drop tables + clear data/); revoke the OIDC token (sign out of the IdP); revoke API keys.' },
  'privacy.s5.title':  { zh: '5. 联系', en: '5. Contact' },
  'privacy.s5.p1':     { zh: '数据相关问题：privacy@agent-gateway.local。',
                            en: 'Data-related inquiries: privacy@agent-gateway.local.' },

  // ---- /settings ----
  'settings.title':     { zh: '凭据与租户', en: 'Credentials & tenant' },
  'settings.subtitle':  { zh: 'API Key 与租户 ID 保存在 localStorage', en: 'API Key & tenant ID live in localStorage' },
  'settings.alert':     { zh: '修改后将立即作用于全部后续请求，401 会自动清空。', en: 'Changes apply to all subsequent requests. 401 auto-clears.' },
  'settings.apiKey':    { zh: 'X-API-Key', en: 'X-API-Key' },
  'settings.tenantId':  { zh: 'X-Tenant-Id', en: 'X-Tenant-Id' },
  'settings.adminTok':  { zh: 'X-Admin-Token', en: 'X-Admin-Token' },
  'settings.adminTokHint': { zh: '管理端点独立凭据（gateway.security.admin-token）；留空 = 后端未启用管理鉴权',
                             en: 'Admin-only credential (gateway.security.admin-token). Leave blank = admin auth disabled on backend' },
  'settings.save':      { zh: '保存', en: 'Save' },
  'settings.clear':     { zh: '清除凭据', en: 'Clear credentials' },
  'settings.cleared':   { zh: '已清除', en: 'Cleared' },
  'settings.firstKeyBtn': { zh: '一键签发首把 API Key', en: 'Create first API Key' },
  'settings.tenantIdPh': { zh: 'primary', en: 'primary' },
  'settings.adminTokenPh': { zh: '留空则不发送', en: 'leave blank to skip' },
  'settings.creating':  { zh: '签发中…', en: 'Creating…' },
  'settings.createSuccess': { zh: '已签发首把 API Key', en: 'First API Key created' },
  'settings.issuedKeyHint': { zh: 'Key 已签发并自动写入本地', en: 'Key created and stored locally' },
  'settings.keyNote':   { zh: '完整值已写入 localStorage.agent-gateway.apiKey · 下次启动自动带入',
                          en: 'Full value in localStorage.agent-gateway.apiKey, auto-loaded on next start' },
  'settings.refreshStatus': { zh: '我已有 Key · 刷新状态', en: 'I have a key · refresh status' },

  // ---- /demo ----
  'demo.subtitle':   { zh: 'AI Agent 调用的统一网关：路由、限流、计费、审计', en: 'Unified gateway for AI Agents: routing, rate limits, billing, audit' },
  'demo.ctaTitle':   { zh: '试用 Demo · 无需注册', en: 'Try the Demo · no signup' },
  'demo.ctaBody':    { zh: '点击下方按钮，本系统会为你创建一个独立的 demo 租户（独立数据隔离），包含：',
                       en: 'Click below to create an isolated demo tenant (separate data) with:' },
  'demo.ctaItem1':   { zh: 'API Key（24h 有效期）', en: 'API Key (24h validity)' },
  'demo.ctaItem2':   { zh: 'Admin 账号（OWNER 角色，可访问管理端）', en: 'Admin account (OWNER role, full admin access)' },
  'demo.ctaItem3':   { zh: '预置的 echo-agent 和示例模型', en: 'Pre-installed echo-agent & sample models' },
  'demo.ctaWarn':    { zh: '注意', en: 'Note' },
  'demo.ctaWarnBody':{ zh: 'Demo 数据 24 小时后自动清理；正式使用请通过设置页绑定你的真实账号。',
                       en: 'Demo data is auto-cleaned after 24h. For production, sign up and bind your real account in Settings.' },
  'demo.creating':   { zh: '创建中…', en: 'Creating…' },
  'demo.tryBtn':      { zh: '一键试用 Demo', en: 'Try the Demo' },
  'demo.disabled':    { zh: 'Demo 模式未启用', en: 'Demo mode disabled' },
  'demo.disabledDesc':{ zh: '请联系管理员开启 GATEWAY_DEMO_ENABLED 环境变量；或直接登录。',
                         en: 'Ask your admin to enable GATEWAY_DEMO_ENABLED, or sign in directly.' },
  'demo.haveAccount': { zh: '已有账号？', en: 'Already have an account?' },
  'demo.haveAccountDesc': { zh: '在「设置」页填入你的 API Key + 租户 ID + Admin Token 即可登录管理端。',
                            en: 'Fill API Key + tenant ID + Admin Token in Settings, then sign in.' },
  'demo.adminLogin':  { zh: 'Admin 登录', en: 'Admin login' },
  'demo.signupBtn':   { zh: '注册正式账号', en: 'Sign up' },

  // ---- /login ----
  'login.title':          { zh: 'Admin 登录', en: 'Admin login' },
  'login.subtitle':       { zh: '多 Admin 账号 + PBKDF2 密码哈希 + RBAC', en: 'Multi-Admin accounts + PBKDF2 hashing + RBAC' },
  'login.loggedIn':       { zh: '已登录', en: 'Logged in' },
  'login.loggedInMsg':    { zh: '已登录 · 浏览器已保存 Admin Token', en: 'Logged in · Admin token saved in this browser' },
  'login.loggedInDesc':   { zh: '可直接访问 /admin-users /teams /prompts /datasets /feedback 等管理端', en: 'Visit /admin-users /teams /prompts /datasets /feedback and more' },
  'login.ssoStarting':    { zh: '正在跳转企业登录…', en: 'Redirecting to SSO…' },
  'login.ssoCta':         { zh: '用 {name} 登录', en: 'Sign in with {name}' },
  'login.ssoDefault':     { zh: '企业账号', en: 'Enterprise SSO' },
  'login.ssoHint':        { zh: 'SSO 走 OAuth2 Authorization Code Flow，浏览器重定向到企业 IdP 登录',
                            en: 'SSO uses OAuth2 Authorization Code Flow; browser redirects to your IdP' },
  'login.tenant':         { zh: '租户', en: 'Tenant' },
  'login.email':          { zh: '邮箱', en: 'Email' },
  'login.emailRequired':  { zh: '请输入邮箱', en: 'Email required' },
  'login.emailInvalid':   { zh: '邮箱格式不正确', en: 'Invalid email format' },
  'login.password':       { zh: '密码', en: 'Password' },
  'login.passwordRequired':{ zh: '请输入密码', en: 'Password required' },
  'login.passwordMin':    { zh: '密码至少 8 位', en: 'Password must be at least 8 chars' },
  'login.passwordLogin':  { zh: '密码登录', en: 'Sign in' },
  'login.firstDeploy':    { zh: '首次部署?', en: 'First deploy?' },
  'login.firstDeployDesc':{ zh: 'R12 #1 默认 AdminToken 兼容路径仍可用(任意非空 token 当 OWNER);本登录页启用后走真鉴权。',
                            en: 'Default AdminToken compat path still works (any non-empty token is OWNER); this page is enforced after enabling.' },
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
