/**
 * request.ts — 统一 fetch 封装
 * - 自动注入 X-API-Key（localStorage）
 * - 自动注入 X-Tenant-Id（context/localStorage）
 * - 错误归一为 ApiError
 * - 默认 10s 超时
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly payload?: unknown;

  constructor(status: number, message: string, code?: string, payload?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    if (code) this.code = code;
    if (payload !== undefined) this.payload = payload;
  }
}

const KEY_API_KEY = 'agent-gateway.apiKey';
const KEY_TENANT = 'agent-gateway.tenant';
const KEY_ADMIN_TOKEN = 'agent-gateway.adminToken';

/**
 * 安全存储层：浏览器禁用 Cookie/网站数据（如 Safari「阻止所有 Cookie」、
 * 隐私模式配额限制）时 window.localStorage 为 null 或访问抛错。
 * 此时降级为内存 Map（刷新后丢失，但不至于白屏/组件崩溃）。
 */
const memoryStore = new Map<string, string>();
const safeStorage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = (() => {
  try {
    const ls = globalThis.localStorage;
    if (ls && typeof ls.getItem === 'function') {
      // 探测一次真实可用性（部分浏览器返回对象但读写抛 SecurityError）
      const probe = '__agent-gateway-probe__';
      ls.setItem(probe, '1');
      ls.removeItem(probe);
      return ls;
    }
  } catch {
    /* fallthrough 到内存回退 */
  }
  // eslint-disable-next-line no-console
  console.warn('[agent-gateway] localStorage 不可用，已降级为内存存储（刷新后丢失）');
  return {
    getItem: (k: string) => memoryStore.get(k) ?? null,
    setItem: (k: string, v: string) => void memoryStore.set(k, v),
    removeItem: (k: string) => void memoryStore.delete(k),
  };
})();

/**
 * 演示 key：本地开发开箱即用（首次访问自动预填，让 Dashboard 直接有数据）。
 * 生产部署前移除（或由后端签发流程替换）。
 */
const DEMO_KEY = 'sk-demo-primary-0001';

export function getApiKey(): string {
  const v = safeStorage.getItem(KEY_API_KEY);
  if (v) return v;
  // 未配置时预填演示 key（仅一次写入，用户可在 Settings 覆盖/清除）
  safeStorage.setItem(KEY_API_KEY, DEMO_KEY);
  return DEMO_KEY;
}

export function setApiKey(v: string): void {
  if (v) safeStorage.setItem(KEY_API_KEY, v);
  else safeStorage.removeItem(KEY_API_KEY);
}

export function getTenant(): string {
  return safeStorage.getItem(KEY_TENANT) ?? 'primary';
}

export function setTenant(v: string): void {
  if (v) safeStorage.setItem(KEY_TENANT, v);
  else safeStorage.removeItem(KEY_TENANT);
}

/** 管理端凭据（X-Admin-Token）：与终端用户 API Key 独立，默认空=未配置（不发送头）。 */
export function getAdminToken(): string {
  return safeStorage.getItem(KEY_ADMIN_TOKEN) ?? '';
}

export function setAdminToken(v: string): void {
  if (v) safeStorage.setItem(KEY_ADMIN_TOKEN, v);
  else safeStorage.removeItem(KEY_ADMIN_TOKEN);
}

export function clearAuth(): void {
  safeStorage.removeItem(KEY_API_KEY);
  safeStorage.removeItem(KEY_TENANT);
  // 注意：adminToken 不随 401 清除——它是独立的管理凭据，误清会把运营台锁死
}

export interface RequestOptions extends Omit<RequestInit, 'signal'> {
  timeout?: number;
  signal?: AbortSignal;
  /** 查询参数，自动拼到 path 上 */
  params?: Record<string, unknown>;
}

/**
 * 将 params 对象序列化为 URL 查询串并 append 到 path。
 * - undefined / null / 空字符串 自动跳过
 * - 数组以 `key=v1&key=v2` 展开
 */
function appendQuery(path: string, params: Record<string, unknown> | undefined): string {
  if (!params) return path;
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      for (const item of v) {
        if (item === undefined || item === null || item === '') continue;
        usp.append(k, String(item));
      }
    } else {
      usp.append(k, String(v));
    }
  }
  const qs = usp.toString();
  if (!qs) return path;
  return path.includes('?') ? `${path}&${qs}` : `${path}?${qs}`;
}

const API = '/v1';
const DEFAULT_TIMEOUT = 10_000;

export async function request<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { timeout = DEFAULT_TIMEOUT, signal, headers, params, ...rest } = options;

  // external abort via AbortController so timeout + caller signal combine
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error('timeout')), timeout);
  if (signal) {
    if (signal.aborted) controller.abort(signal.reason);
    signal.addEventListener('abort', () => controller.abort(signal.reason));
  }

  const finalHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(headers as Record<string, string> | undefined),
  };
  const apiKey = getApiKey();
  if (apiKey) finalHeaders['X-API-Key'] = apiKey;
  const tenant = getTenant();
  if (tenant) finalHeaders['X-Tenant-Id'] = tenant;

  const fullPath = appendQuery(path, params as Record<string, unknown> | undefined);

  // 管理端凭据：仅对 /admin/** 路径自动附带，与 X-API-Key 相互独立
  const adminToken = getAdminToken();
  if (adminToken && fullPath.startsWith('/admin')) finalHeaders['X-Admin-Token'] = adminToken;

  try {
    const res = await fetch(`${API}${fullPath}`, { ...rest, headers: finalHeaders, signal: controller.signal });
    if (!res.ok) {
      let body: any = null;
      try {
        body = await res.json();
      } catch {
        // not json
      }
      if (res.status === 401) {
        clearAuth();
      }
      throw new ApiError(
        res.status,
        body?.message ?? body?.msg ?? `HTTP ${res.status}`,
        body?.code,
        body,
      );
    }
    if (res.status === 204) return undefined as T;
    const ct = res.headers.get('content-type') ?? '';
    if (ct.includes('application/json')) {
      return (await res.json()) as T;
    }
    return (await res.text()) as unknown as T;
  } finally {
    clearTimeout(timer);
  }
}

export const http = {
  get: <T,>(path: string, opts?: RequestOptions) => request<T>(path, { ...opts, method: 'GET' }),
  post: <T,>(path: string, body?: unknown, opts?: RequestOptions) =>
    request<T>(path, { ...opts, method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined }),
  put: <T,>(path: string, body?: unknown, opts?: RequestOptions) =>
    request<T>(path, { ...opts, method: 'PUT', body: body !== undefined ? JSON.stringify(body) : undefined }),
  delete: <T,>(path: string, opts?: RequestOptions) =>
    request<T>(path, { ...opts, method: 'DELETE' }),
};

/* ────────────────────────────────────────────────────────────────────────────
 *  Round 10 B-2 — 错误通知中心
 *
 *  设计目标：fetch 失败不再只 `message.error` toast（3 秒自动消失），而是
 *  1) 进常驻通知中心（用户切走 tab 也能事后回看）
 *  2) 同步弹一条短 toast 作即时反馈
 *  3) 同错误 5 分钟内去重（避免重复轰炸）
 *
 *  这是 useResourceList 的默认错误兜底；调用方想自己处理可传 onError 跳过。
 * ──────────────────────────────────────────────────────────────────────────── */

const errorDedup = new Map<string, number>(); // key → lastTimestampMs
const DEDUP_WINDOW_MS = 5 * 60_000;

function dedupKey(err: Error, context: string): string {
  return `${context}::${err.message}`;
}

export function notifyError(err: Error, context: string): void {
  const key = dedupKey(err, context);
  const now = Date.now();
  const last = errorDedup.get(key) ?? 0;
  if (now - last < DEDUP_WINDOW_MS) return; // 去重
  errorDedup.set(key, now);

  // toast 即时反馈（antd 静态 API，方便 hook/非 React 上下文调用）
  try {
    const { message } = require('antd');
    message.error(`${context}：${err.message}`);
  } catch {
    /* antd 未在上下文：静默吞掉（仅去重记录仍生效） */
  }

  // 常驻通知中心 — 由 NotificationCenter 组件在 mount 时拉 pending
  try {
    const event = new CustomEvent('agent-gateway:error', { detail: { err, context, at: now } });
    window.dispatchEvent(event);
  } catch {
    /* SSR / 测试环境无 window：吞掉 */
  }
}

/** 测试 helper：清空错误去重缓存；vitest 跨用例隔离用 */
export function __resetErrorDedup(): void {
  errorDedup.clear();
}