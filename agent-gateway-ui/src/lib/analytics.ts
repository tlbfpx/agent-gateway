/**
 * analytics.ts — 漏斗埋点（spec §funnel-analytics round 35）。
 *
 * 设计：5 个事件覆盖 demo→signup→team 升级漏斗
 *   - demo_click        /demo CTA 点击
 *   - demo_bootstrap    /demo 后端成功返回
 *   - signup_start      /signup 表单提交
 *   - signup_success    /signup 后端成功 + localStorage 落库
 *   - pricing_cta_click  /pricing 档位 CTA 点击
 *
 * 配置：
 *   - 没配 POSTHOG_HOST / POSTHOG_API_KEY → 所有事件 console.debug（不联网）
 *   - 配了 → POST /capture（PostHog 兼容 JSON 格式）
 *
 * 设计哲学：
 *   - 不阻塞 UI：fetch keepalive: true
 *   - 失败只 console.warn，不 throw
 *   - 不收集 PII：事件不带 email / token；用 anonymous distinct_id（首次访问 localStorage 随机生成）
 */

export type FunnelEvent =
  | 'demo_click'
  | 'demo_bootstrap'
  | 'signup_start'
  | 'signup_success'
  | 'pricing_cta_click';

const POSTHOG_HOST = (import.meta as any).env?.VITE_POSTHOG_HOST
    || (window as any).__POSTHOG_HOST__;
const POSTHOG_KEY = (import.meta as any).env?.VITE_POSTHOG_API_KEY
    || (window as any).__POSTHOG_API_KEY__;
const ENABLED = !!(POSTHOG_HOST && POSTHOG_KEY);

const DISTINCT_ID_KEY = 'agent-gateway.distinctId';
function distinctId(): string {
  let id = localStorage.getItem(DISTINCT_ID_KEY);
  if (!id) {
    id = (crypto.randomUUID?.() ?? Math.random().toString(36).slice(2));
    try { localStorage.setItem(DISTINCT_ID_KEY, id); } catch { /* ignore */ }
  }
  return id;
}

/** 发送事件到 PostHog /capture；失败仅 warn。 */
export function track(event: FunnelEvent, props: Record<string, unknown> = {}) {
  const payload = {
    api_key: POSTHOG_KEY,
    event,
    properties: {
      distinct_id: distinctId(),
      ...props,
      $lib: 'agent-gateway-ui',
      $current_url: window.location.pathname,
      ts: new Date().toISOString(),
    },
  };
  if (!ENABLED) {
    if (typeof console !== 'undefined') {
      // eslint-disable-next-line no-console
      console.debug('[analytics]', event, props);
    }
    return;
  }
  try {
    fetch(`${POSTHOG_HOST}/capture`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      keepalive: true,
    }).catch((e) => {
      // eslint-disable-next-line no-console
      console.warn('[analytics] capture failed', e);
    });
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn('[analytics] send error', e);
  }
}

export function isAnalyticsEnabled(): boolean {
  return ENABLED;
}