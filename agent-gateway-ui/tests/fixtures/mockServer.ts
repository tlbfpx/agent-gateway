/**
 * tests/fixtures/mockServer.ts
 *
 * 轻量级 fetch mock —— 拦截 /v1/* 请求并基于 seed 数据应答。
 *
 * 设计要点：
 *  1. 不引第三方依赖（no msw），用 globalThis.fetch 替换实现
 *  2. 状态全内存，重置由 installMock({ store }) 提供
 *  3. 路径匹配支持 :id 通配与 ?query
 *  4. 支持动态覆写：tests 可以 spyOn 或 fetchMock.on('POST /admin/agents', () => 400)
 *
 * 用法：
 *   const mock = installMock();
 *   afterEach(() => mock.uninstall());
 *   // 或传自定义 store：
 *   const mock = installMock({ store: myStore });
 */

import { resetSeed } from './seed';
import type { SeedStore } from './seed';
import type { SpanRecord } from '../../src/lib/api/traces';

interface HandlerCtx {
  url: URL;
  method: string;
  body: unknown;
  store: SeedStore;
}

type Handler = (ctx: HandlerCtx) => Response | Promise<Response> | null | undefined;

interface RouteOverride {
  method: string;
  path: string;
  handler: Handler;
}

const uuid = () =>
  // 时间戳 + 随机
  `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;

/**
 * 将 path 模板 (e.g. /admin/agents/:id) 编译成正则，并提取变量名。
 */
function compilePath(template: string): { re: RegExp; keys: string[] } {
  const keys: string[] = [];
  const reSrc = template.replace(/:[A-Za-z_][A-Za-z0-9_]*/g, (m) => {
    keys.push(m.slice(1));
    return '([^/]+)';
  });
  return { re: new RegExp(`^${reSrc}$`), keys };
}

/** 路径的最后一段（去除前后 /v1/） */
function pathLast(p: string): string {
  const parts = p.split('?')[0].split('/').filter(Boolean);
  return parts[parts.length - 1] ?? '';
}
/** 找 path 中 segment 后紧跟的那段 id */
function pathSegmentAfter(p: string, segment: string): string {
  const parts = p.split('?')[0].split('/').filter(Boolean);
  const i = parts.indexOf(segment);
  return i >= 0 && i + 1 < parts.length ? parts[i + 1] : '';
}

/** 标准 JSON 响应 */
const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  });
const fail = (status: number, message: string, code?: string) =>
  json({ message, code }, { status });

/**
 * 默认路由表 —— 与 src/lib/api/* 一一对应
 *  - 列表类：返回 Paged<T> 形状
 *  - 写操作：返回新对象或 { deleted: id }
 */
function defaultRoutes(store: SeedStore) {
  return {
    /* —— models —— */
    'GET /admin/models': () => json(store.models),

    /* —— api-keys —— */
    'GET /admin/api-keys': () =>
      json(
        store.apiKeys.map((k) => ({
          // MVP：mock 给所有 key 默认带 balanceCny + monthlyQuotaCny，
          // 避免 ApiKeys/List 的「余额」列空着。
          balanceCny: 0,
          monthlyQuotaCny: 1000,
          ...k,
        })),
      ),
    'POST /admin/api-keys': (ctx: HandlerCtx) => {
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const created = {
        id: `pk_live_${uuid()}`,
        owner: (body.owner as string) ?? 'admin',
        tenant: (body.tenant as string) ?? 'primary',
        enabled: true,
        models: body.models as string[] | undefined,
        rateLimitRpm: body.rateLimitRpm as number | undefined,
        expiresAt: body.expiresAt as string | undefined,
        createdAt: new Date().toISOString(),
        value: `pk_live_${uuid()}_plaintext`,
        balanceCny: 0,
        monthlyQuotaCny: 1000,
      };
      store.apiKeys.unshift(created as never);
      return json({ created });
    },
    'DELETE /admin/api-keys/:id': (ctx: HandlerCtx) => {
      const id = ctx.url.pathname.split('/').pop()!;
      store.apiKeys = store.apiKeys.filter((k) => k.id !== id);
      return json({ deleted: id });
    },

    /* —— virtual-keys (Round6：预付费充值流程) —— */
    'POST /admin/virtual-keys/:id/topup': (ctx: HandlerCtx) => {
      const id = pathSegmentAfter(ctx.url.pathname, 'virtual-keys');
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const amountCny = Number(body.amountCny ?? 0);
      return json({
        checkoutUrl: `https://checkout.stripe.com/c/test_${id}`,
        amountCny,
        sessionId: `cs_test_${uuid()}`,
      });
    },
    'GET /admin/virtual-keys/:id/usage': (ctx: HandlerCtx) => {
      // MVP：复用 audit 前 5 条作为占位 usage 记录
      return json(
        store.audit.slice(0, 5).map((a) => ({
          recordId: a.id,
          tenant: { value: 'primary' },
          user: { value: a.actor ?? 'unknown' },
          model: { value: 'gpt-4o' },
          agentName: a.resource ?? 'chat',
          timestamp: a.ts,
          tokensIn: 0,
          tokensOut: 0,
          cost: 0,
          unitPriceIn: 0,
          unitPriceOut: 0,
        })),
      );
    },

    /* —— webhooks —— */
    'GET /admin/webhooks': () => json(store.webhooks),
    'POST /admin/webhooks': (ctx: HandlerCtx) => {
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const created = {
        id: `wh_${uuid()}`,
        url: body.url as string,
        events: (body.events as string[]) ?? [],
        enabled: body.enabled !== false,
        tenant: (body.tenant as string) ?? 'primary',
        secret: `whsec_${uuid()}`,
        dlqCount: 0,
      };
      store.webhooks.unshift(created as never);
      return json(created);
    },
    'DELETE /admin/webhooks/:id': (ctx: HandlerCtx) => {
      const id = ctx.url.pathname.split('/').pop()!;
      store.webhooks = store.webhooks.filter((w) => w.id !== id);
      return json({ deleted: id });
    },

    /* —— audit —— */
    'GET /admin/audit': () => json(store.audit),
    'GET /admin/audit/logs': (ctx: HandlerCtx) => {
      const tenant = ctx.url.searchParams.get('tenant') ?? 'primary';
      const limit = Number(ctx.url.searchParams.get('limit') ?? '50');
      const offset = Number(ctx.url.searchParams.get('offset') ?? '0');
      const type = ctx.url.searchParams.get('type');
      const result = ctx.url.searchParams.get('result')?.toUpperCase();
      const from = ctx.url.searchParams.get('from');
      const keyword = ctx.url.searchParams.get('keyword');
      const items = store.audit
        .filter((a) => !a.resource || (a as { tenant?: string }).tenant === tenant || tenant === 'primary')
        .filter((a) => !type || a.type === type)
        .filter((a) => !result || (a.result ?? '').toUpperCase() === result)
        .filter((a) => {
          if (!from) return true;
          const t = new Date(a.ts).getTime();
          return Number.isNaN(t) || t >= new Date(from).getTime();
        })
        .filter((a) => {
          if (!keyword) return true;
          const k = keyword.toLowerCase();
          return (
            (a.actor ?? '').toLowerCase().includes(k) ||
            (a.resource ?? '').toLowerCase().includes(k) ||
            (a.action ?? '').toLowerCase().includes(k) ||
            (a.id ?? '').toLowerCase().includes(k)
          );
        })
        .slice(offset, offset + limit)
        .map((a) => ({
          eventId: a.id,
          actor: a.actor,
          type: a.type,
          time: a.ts,
          resource: a.resource,
          action: a.action,
          result: a.result,
          detail: a.reason ?? '',
          tenant: 'primary',
        }));
      return json(items);
    },

    /* —— config-history —— */
    'GET /admin/config/:name/versions': (ctx: HandlerCtx) => {
      const name = pathSegmentAfter(ctx.url.pathname, 'config');
      const items = store.configVersions.filter((v) => v.name === name).map((v) => ({
        version: v.version,
        at: v.ts,
        size: 1024 + v.version * 16,
      }));
      return json(items);
    },
    'GET /admin/config/:name/diff': (ctx: HandlerCtx) => {
      const name = pathSegmentAfter(ctx.url.pathname, 'config');
      const a = ctx.url.searchParams.get('a');
      const b = ctx.url.searchParams.get('b');
      return json({
        name,
        a,
        b,
        fields: { model: [`v${a}`, `v${b}`] },
      });
    },
    'POST /admin/config/:name/rollback': (ctx: HandlerCtx) => {
      const name = pathSegmentAfter(ctx.url.pathname, 'config');
      const url = new URL(ctx.url.toString());
      const versionParam = url.searchParams.get('version') ?? '';
      const count = store.configVersions.filter((v) => v.name === name).length ?? 0;
      const summary = `回滚至 v${versionParam}`;
      // seed 全字段 + API 字段；同时存到 store（两边都能识别）
      const next = {
        name,
        version: count + 1,
        at: new Date().toISOString(),
        size: 1024,
        author: 'admin@primary',
        ts: new Date().toISOString(),
        summary,
      };
      store.configVersions.unshift(next as SeedStore['configVersions'][number]);
      // API 返回形状（仅 page 接口关心的字段）
      return json({ version: next.version, at: next.at, size: next.size });
    },

    /* —— rbac —— */
    'POST /admin/rbac/preview': (ctx: HandlerCtx) => {
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const deny = !!body.tenant && String(body.tenant) !== 'primary';
      return json(deny
        ? { allowed: false, reason: 'cross-tenant 隔离策略拒绝' }
        : { allowed: true });
    },

    /* —— agents —— */
    'GET /agents': () => json(store.agents.map((a) => ({
      name: a.name,
      description: a.description,
      skills: a.skills,
      version: a.version,
      available: a.available,
      endpoint: a.endpoint,
    }))),
    'GET /admin/discovery': () => json(store.agents.map((a) => ({
      name: a.name,
      description: a.description,
      skills: a.skills,
      version: a.version,
      available: a.available,
      endpoint: a.endpoint,
    }))),
    'GET /admin/agents': (ctx: HandlerCtx) => {
      const q = ctx.url.searchParams.get('q')?.toLowerCase() ?? '';
      const source = ctx.url.searchParams.get('source');
      const page = Number(ctx.url.searchParams.get('page') ?? '1');
      const pageSize = Number(ctx.url.searchParams.get('pageSize') ?? '20');
      let items = store.agents.slice();
      if (q) {
        items = items.filter((a) =>
          a.name.toLowerCase().includes(q) ||
          a.endpoint.toLowerCase().includes(q) ||
          a.tags.some((t) => t.toLowerCase().includes(q)),
        );
      }
      if (source) items = items.filter((a) => a.source === source);
      const total = items.length;
      const start = (page - 1) * pageSize;
      return json({
        items: items.slice(start, start + pageSize),
        total,
        page,
        pageSize,
      });
    },
    'GET /admin/agents/:id': (ctx: HandlerCtx) => {
      const id = ctx.url.pathname.split('/').pop()!;
      const a = store.agents.find((x) => x.id === id);
      return a ? json(a) : fail(404, 'not found');
    },
    'POST /admin/agents': (ctx: HandlerCtx) => {
      const body = (ctx.body ?? {}) as Record<string, unknown>;
      const created = {
        ...(body as object),
        id: `ag_${uuid()}`,
        enabled: body.enabled !== false,
        available: true,
        source: 'manual',
        owner: 'admin@primary',
        tags: (body.tags as string[]) ?? [],
        skills: (body.skills as string[]) ?? [],
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      } as SeedStore['agents'][number];
      store.agents.unshift(created);
      return json(created, { status: 201 });
    },
    'PUT /admin/agents/:id': (ctx: HandlerCtx) => {
      const id = pathLast(ctx.url.pathname);
      const idx = store.agents.findIndex((a) => a.id === id);
      if (idx < 0) return fail(404, 'not found');
      const next = { ...store.agents[idx], ...(ctx.body as object), updatedAt: new Date().toISOString() };
      store.agents[idx] = next;
      return json(next);
    },
    'DELETE /admin/agents/:id': (ctx: HandlerCtx) => {
      const id = pathLast(ctx.url.pathname);
      const before = store.agents.length;
      store.agents = store.agents.filter((a) => a.id !== id);
      if (store.agents.length === before) return fail(404, 'not found');
      return json({ deleted: id });
    },
    'POST /admin/agents/:id/availability': (ctx: HandlerCtx) => {
      const id = pathSegmentAfter(ctx.url.pathname, 'agents');
      const a = store.agents.find((x) => x.id === id);
      if (!a) return fail(404, 'not found');
      const body = (ctx.body ?? {}) as { enabled?: boolean };
      a.enabled = body.enabled !== false;
      a.updatedAt = new Date().toISOString();
      return json(a);
    },
    'POST /admin/agents/:id/test': (ctx: HandlerCtx) => {
      const id = pathSegmentAfter(ctx.url.pathname, 'agents');
      const a = store.agents.find((x) => x.id === id);
      if (!a) return fail(404, 'not found');
      const ok = a.enabled && a.available;
      return json(ok
        ? { ok: true, latencyMs: 18 + Math.floor(Math.random() * 50) }
        : { ok: false, message: 'agent 不可用' });
    },

    /* —— chat —— */
    // 注意：lib/api/chat.ts 用的路径是 /sessions（不带 /chat 前缀），不是 /chat/sessions
    // 列表条目对齐后端 SessionApiController：sessionId / lastActiveAt / title / model / messageCount
    'GET /sessions': () =>
      json(
        store.chatSessions.map((s) => ({
          sessionId: s.id,
          lastActiveAt: s.lastActiveAt,
          title: s.title ?? '新对话',
          model: 'gpt-4o',
          messageCount: s.title && s.title !== '新对话' ? 2 : 0,
        })),
      ),
    'POST /sessions': (ctx: HandlerCtx) => {
      const url = new URL(ctx.url.toString());
      const model = url.searchParams.get('model') ?? '';
      if (!model) return fail(400, 'model is required', 'MISSING_MODEL');
      const newS = {
        id: `s_${uuid()}`,
        title: '新会话',
        lastActiveAt: new Date().toISOString(),
      };
      store.chatSessions.unshift(newS as SeedStore['chatSessions'][number]);
      return json({ sessionId: newS.id, createdAt: newS.lastActiveAt }, { status: 201 });
    },
    'GET /sessions/:id/messages': (ctx: HandlerCtx) => {
      const id = pathLast(ctx.url.pathname);
      return json([]);
    },
    'DELETE /sessions/:id': (ctx: HandlerCtx) => {
      const id = pathLast(ctx.url.pathname);
      store.chatSessions = store.chatSessions.filter((s) => s.id !== id);
      return json({ ok: true });
    },
    'POST /chat/stream': (ctx: HandlerCtx) => {
      // SSE：chunk + done；done 携带 meta（含提示缓存命中标记 cacheHit）
      const cache = store.promptCache;
      const cacheHit = cache.enabled && cache.hits > 0;
      const prompt = ((ctx.body as { prompt?: string } | null)?.prompt ?? '').slice(0, 40);
      const reply = `（mock 回复）已收到：「${prompt}」——来自 mock server 的流式回答。`;
      const sse = [
        'event: chunk',
        `data: ${JSON.stringify({ content: reply.slice(0, 12) })}`,
        '',
        'event: done',
        `data: ${JSON.stringify({
          response: reply,
          meta: { model: 'gpt-4o', tokensIn: 120, tokensOut: 36, cacheHit },
        })}`,
        '',
        '',
      ].join('\n');
      return new Response(sse, { headers: { 'content-type': 'text/event-stream' } });
    },

    /* —— traces (Round7 调用链追踪 UI MVP) —— */
    // 列表：返回 store.traces；若 store.traces 为 undefined 视作"未配置持久化存储"，返回 503 引导
    'GET /admin/traces': (ctx: HandlerCtx) => {
      if ((ctx.store as { traces?: unknown }).traces === undefined) {
        return fail(503, '未配置持久化存储:请配置 observability.storage.jdbc-url');
      }
      const errOnly = ctx.url.searchParams.get('errorOnly') === 'true';
      const operation = ctx.url.searchParams.get('operation');
      let items = (ctx.store.traces ?? []).slice();
      if (errOnly) items = items.filter((t) => t.errorCount > 0);
      if (operation) items = items.filter((t) => t.rootSpanName === operation);
      return json(items);
    },
    'GET /admin/traces/:traceId': (ctx: HandlerCtx) => {
      const traceId = pathSegmentAfter(ctx.url.pathname, 'traces');
      const spans = (ctx.store as { spans?: Record<string, SpanRecord[]> }).spans?.[traceId];
      if (!spans) return fail(404, 'trace not found: ' + traceId);
      return json({ traceId, spans });
    },

    /* —— 提示缓存（gateway.llm.prompt-cache 契约）—— */
    'GET /admin/config/gateway.llm.prompt-cache': () => {
      const { hits: _h, misses: _m, ...cfg } = store.promptCache;
      return json(cfg);
    },
    'GET /actuator/metrics/prompt_cache_hit_total': () =>
      json({
        name: 'prompt_cache_hit_total',
        measurements: [{ statistic: 'COUNT', value: store.promptCache.hits }],
      }),
    'GET /actuator/metrics/prompt_cache_miss_total': () =>
      json({
        name: 'prompt_cache_miss_total',
        measurements: [{ statistic: 'COUNT', value: store.promptCache.misses }],
      }),

    /* —— models —— */
    'GET /models': () => json(store.models.map((m) => ({
      modelId: m.id,
      displayName: m.displayName,
      provider: m.provider,
    }))),

    /* —— health / readiness —— */
    'GET /health': () => json(store.health),
    'GET /ready': () => json(store.ready),

    /* —— settings —— */
    /* settings 实际不调后端 —— X-API-Key / X-Tenant-Id 直接落 localStorage */
  };
}

export interface MockOptions {
  /** 自定义 store（深拷贝 seedStore 后可改写），不传则用全新 resetSeed() */
  store?: SeedStore;
}

export interface MockHandle {
  /** 当前 store（可读写） */
  store: SeedStore;
  /** 追加/覆写路由：tests/on(...) */
  on(method: string, path: string, handler: Handler): void;
  /** 让下一次指定 method+path 返回指定 status + JSON */
  nextReply(method: string, path: string, body: unknown, status?: number): void;
  /** 卸载 mock，恢复原 fetch */
  uninstall(): void;
}

export function installMock(opts: MockOptions = {}): MockHandle {
  const originalFetch = globalThis.fetch;
  const store = opts.store ?? resetSeed();
  const routes = defaultRoutes(store);
  const overrides: RouteOverride[] = [];
  const nextReplies: Array<{ method: string; path: string; body: unknown; status: number }> = [];

  const routeKey = (method: string, path: string) => `${method.toUpperCase()} ${path.split('?')[0]}`;

  function findHandler(method: string, path: string): Handler | undefined {
    // 1. 检查 nextReply（一次性）
    for (let i = 0; i < nextReplies.length; i++) {
      const nr = nextReplies[i];
      if (nr.method.toUpperCase() === method.toUpperCase() && matchPath(nr.path, path)) {
        nextReplies.splice(i, 1);
        return () => json(nr.body, { status: nr.status });
      }
    }
    // 2. 显式 override（last-match wins：后注册的覆盖前注册的，符合 MSW/nock 惯例）
    for (let i = overrides.length - 1; i >= 0; i--) {
      const o = overrides[i];
      if (o.method.toUpperCase() === method.toUpperCase() && matchPath(o.path, path)) {
        return o.handler;
      }
    }
    // 3. 默认表
    for (const key of Object.keys(routes) as (keyof typeof routes)[]) {
      const [m, p] = key.split(' ');
      if (m === method.toUpperCase() && matchPath(p, path)) return routes[key];
    }
    return undefined;
  }

  function matchPath(template: string, real: string): boolean {
    const { re } = compilePath(template);
    // 路由 key 不含 /v1 前缀；请求 URL 带 /v1，这里兼容两种
    const target = real.split('?')[0];
    return re.test(target) || re.test(target.replace(/^\/v1/, ''));
  }

  // 替换 fetch
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    // jsdom 下 Request 构造器对 signal 严格校验且与 AbortController.signal 不互认，
    // 所以读 url/body 时直接走 input，不重建 Request。
    let urlStr: string;
    let method = 'GET';
    let body: unknown = undefined;
    let headers: Record<string, string> = {};

    if (typeof input === 'string') {
      urlStr = input;
    } else if (input instanceof URL) {
      urlStr = input.toString();
    } else {
      // Request
      urlStr = input.url;
      method = input.method;
      try {
        const ct = input.headers.get('content-type') ?? '';
        const txt = await input.clone().text();
        if (txt && ct.includes('application/json')) {
          try { body = JSON.parse(txt); } catch { body = txt; }
        } else if (txt) {
          body = txt;
        }
      } catch { /* ignore */ }
      input.headers.forEach((v, k) => { headers[k] = v; });
    }

    if (init) {
      method = init.method ?? method;
      let ct = '';
      if (init.headers) {
        if (typeof (init.headers as Headers).get === 'function') {
          ct = (init.headers as Headers).get('content-type') ?? '';
          (init.headers as Headers).forEach((v: string, k: string) => {
            headers[k] = v;
          });
        } else {
          const h = init.headers as Record<string, string>;
          for (const k of Object.keys(h)) headers[k] = h[k];
          ct = h['content-type'] ?? h['Content-Type'] ?? '';
        }
      }
      if (init.body != null) {
        if (ct.toLowerCase().includes('application/json')) {
          try { body = JSON.parse(String(init.body)); } catch { body = init.body; }
        } else {
          body = init.body;
        }
      }
    }

    // 合并外部 signal callback（仅把它记录下来，不传给 Route）
    if (init?.signal) {
      const s = init.signal as { aborted?: boolean };
      if (s.aborted) return fail(499, 'aborted');
    }

    const url = new URL(urlStr, 'http://localhost');
    // 我们只拦截 /v1/* 与 /actuator/*（指标计数器）；其他请求放行
    if (!url.pathname.startsWith('/v1/') && !url.pathname.startsWith('/actuator/')) {
      // 走原始 fetch，但要避免 signal 校验问题
      return originalFetch(input as RequestInfo, { ...(init ?? {}), signal: undefined } as RequestInit);
    }

    const handler = findHandler(method, url.pathname);
    if (!handler) {
      return fail(404, `mock: no route ${method} ${url.pathname}`, 'NOT_FOUND');
    }
    const result = await handler({ url, method, body, store });
    return (result as Response) ?? fail(500, 'mock handler returned null', 'NO_HANDLER');
  }) as typeof fetch;

  return {
    store,
    on(method, path, handler) {
      overrides.push({ method, path, handler });
    },
    nextReply(method, path, body, status = 200) {
      nextReplies.push({ method, path, body, status });
    },
    uninstall() {
      globalThis.fetch = originalFetch;
    },
  };
}
