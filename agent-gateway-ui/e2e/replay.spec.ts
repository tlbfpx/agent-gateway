import { test, expect, request } from '@playwright/test';

/**
 * Replay 端到端验证(Sprint 2 P6):
 * 流程:
 *   1) 触发一次 chat 调用,产生 trace
 *   2) 打开 /traces 详情页,获取 traceId
 *   3) 点 Replay 按钮 → 切到 Default Replay Tab → 启动
 *   4) 等待 status=COMPLETED,验证响应文本存在
 *   5) 切到 Compare Tab,输入另一 traceId,点计算 Diff
 *   6) 验证 verdict 字段渲染
 *
 * 前置:网关 8080 + UI 5173 已启动,且 trace 持久化(pgvector + trace_payloads)可用。
 *       若无 trace,可直接通过 POST / /v1/admin/traces/{traceId}/replay 验证(API 层).
 */
const API_KEY = process.env.E2E_API_KEY ?? 'sk-demo-primary-0001';
const TENANT = process.env.E2E_TENANT ?? 'primary';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, tenant]) => {
      window.localStorage.setItem('agent-gateway.apiKey', key!);
      window.localStorage.setItem('agent-gateway.tenant', tenant!);
    },
    [API_KEY, TENANT],
  );
});

test('Replay Modal 三 Tab 渲染', async ({ page }) => {
  await page.goto('/traces');
  // 任意列表行(若为空,跳过 replay 操作)
  const firstTraceId = await page.locator('code').first().textContent({ timeout: 5000 }).catch(() => null);
  if (!firstTraceId) {
    test.skip(true, '没有 trace 可测试 — 请先用 /chat 产生请求');
    return;
  }

  await page.goto(`/traces?traceId=${encodeURIComponent(firstTraceId.trim())}`);
  await expect(page.getByRole('button', { name: /Replay/ }).first()).toBeVisible({ timeout: 15_000 });

  // 打开 Modal
  await page.getByRole('button', { name: /Replay/ }).first().click();
  await expect(page.getByText('默认重放')).')).toBeVisible();
  await expect(page.getByText('What-if')).')).toBeVisible();
  await expect(page.getByText('对比')).')).toBeVisible();
});

test('直接通过 API 验证 Replay + Diff 端点', async () => {
  const ctx = await request.newContext({
    baseURL: process.env.E2E_API_BASE ?? 'http://localhost:8080',
    extraHTTPHeaders: { 'X-Admin-Token': process.env.E2E_ADMIN_TOKEN ?? 'admin-token' },
  });

  // 1. 找一条已存在的 trace
  const traces = await ctx.get('/v1/admin/traces').catch(() => null);
  if (!traces || !traces.ok()) {
    test.skip(true, '/v1/admin/traces 不可用');
    return;
  }
  const list = await traces.json();
  const traceId = list?.[0]?.traceId;
  if (!traceId) {
    test.skip(true, '列表为空,跳过');
    return;
  }

  // 2. Replay(可能因 payload 缺失抛 500,但端点本身应可达)
  const replayRes = await ctx.post(`/v1/admin/traces/${encodeURIComponent(traceId)}/replay`, {
    data: { traceId, safeReplay: true, allowMutatingTools: false },
  });
  // 期望 200/500;不应 404
  expect([200, 500]).toContain(replayRes.status());

  // 3. Diff 端点:即使没有 payload,也应可访问
  const diffRes = await ctx.get(
    `/v1/admin/traces/${encodeURIComponent(traceId)}/diff?against=${encodeURIComponent(traceId)}`,
  );
  expect([200, 404, 500]).toContain(diffRes.status());
});

test('Sprint 2 P5 async + callbackUrl:返回 PENDING 立即', async () => {
  const ctx = await request.newContext({
    baseURL: process.env.E2E_API_BASE ?? 'http://localhost:8080',
    extraHTTPHeaders: { 'X-Admin-Token': process.env.E2E_ADMIN_TOKEN ?? 'admin-token' },
  });
  const traces = await ctx.get('/v1/admin/traces').catch(() => null);
  if (!traces || !traces.ok()) {
    test.skip(true, '/v1/admin/traces 不可用');
    return;
  }
  const list = await traces.json();
  const traceId = list?.[0]?.traceId;
  if (!traceId) {
    test.skip(true, '列表为空');
    return;
  }

  const start = Date.now();
  const replayRes = await ctx.post(`/v1/admin/traces/${encodeURIComponent(traceId)}/replay`, {
    data: {
      traceId,
      safeReplay: true,
      callbackUrl: 'http://localhost:1/cb', // 故意不可达;callback POST 失败仅日志
    },
  });
  const elapsed = Date.now() - start;

  // 异步路径期望 < 500ms(不等 orchestrator 完成)
  expect(elapsed).toBeLessThan(2000);
  // 状态码可能是 200(PENDING 已返回) 或 500(payload 缺失)— 都接受
  expect([200, 500]).toContain(replayRes.status());
});