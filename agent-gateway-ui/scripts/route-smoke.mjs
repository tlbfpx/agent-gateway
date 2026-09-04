#!/usr/bin/env node
/**
 * route-smoke.mjs — 全路由 console/network 巡检脚本
 *
 * 启动无头 Chromium，逐路由跳转，捕获 console 报错 + 网络失败 + 页面未捕获异常，
 * 截图保存到 test-results/route-smoke/<route>.png，并打印汇总表。
 *
 * 退出码：发现 1+ 严重错误时为 1，否则 0。
 */
import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
const OUT_DIR = path.resolve('test-results/route-smoke');
const REPORT_FILE = path.join(OUT_DIR, 'report.json');
const SUMMARY_FILE = path.join(OUT_DIR, 'summary.md');

/** 与 routes.tsx 对齐的路由清单（保持顺序以便 diff）。 */
const ROUTES = [
  '/demo',
  '/signup',
  '/status',
  '/dashboard',
  '/models',
  '/api-keys',
  '/webhooks',
  '/audit',
  '/config-history',
  '/config-reloader',
  '/cache',
  '/guardrails',
  '/rbac',
  '/roles',
  '/user-bindings',
  '/policies',
  '/ratelimit',
  '/traces',
  '/workflows',
  '/alerts',
  '/help',
  '/discovery',
  '/agents',
  '/chat',
  '/cost',
  '/cost/reconcile',
  '/budgets',
  '/api',
  '/settings',
  '/health',
  '/feedback',
  '/admin-users',
  '/teams',
  '/prompts',
  '/datasets',
  '/mcp',
  '/login',
  '/k8s',
  '/plugins',
];

const IGNORE_PATTERNS = [
  // 已知良性：开发期 antd/devtools/vite 提示
  /\[antd:/i,
  /\[vite\]/i,
  /Download the React DevTools/i,
  // antd React 18 findDOMNode 兼容（由 antd 5.x 内部触发，团队已记录）
  /findDOMNode/i,
  // antd Form: 用Form 实例未传form prop 警告（多个页面的搜索/筛选表单共用，影响面广）
  /is not connected to any Form element/i,
];

/** 当前运行实例需要 admin token（GATEWAY_ADMIN_TOKEN 启用）。
 * 任何非空 token 都过得了 AdminTokenFilter，但 AdminAuthController.login 之后用
 * bcrypt token，本地 admin token 进不去 /v1/admin/auth/me。
 * 我们关心的是「页面控制台无报错」，把这条独立端点排除即可。 */
const SMOKE_ADMIN_TOKEN = 'sk-smoke-admin-001';
const EXPECTED_AUTH_NOISE = [
  // /v1/admin/auth/me 需 bcrypt token，smoke 用静态 token 必然 401，console 报错可接受
  '/v1/admin/auth/me',
];

/**
 * 把「已知/预期会失败的请求」打成 console error 但并不表示页面损坏。
 * - /v1/admin/* 在运行实例要求 admin token 但 smoke 无 bcrypt token 时返回 401，
 *   Chromium 仍会把 401 计入 console.error。代码层（catch ApiError）已优雅降级。
 * - /actuator/metrics/prompt_cache_*_total 在 Spring Boot 默认未注册此 Micrometer Counter。
 *   代码层（fetchCounter）已 try-catch 返回 null。
 *
 * 真正的「页面错误」应只看：
 *   1. pageerror（未捕获 JS 异常）
 *   2. errBoundaryVisible（ErrorBoundary 兜底触发）
 *   3. main 元素不可见（白屏）
 *   4. 状态码 5xx / 网络层中断
 */
const EXPECTED_NOISE_URLS = [
  /^\/v1\/admin\//,
  /^\/actuator\/metrics\/(prompt_cache_hit_total|prompt_cache_miss_total)$/,
];

function isExpectedNoise(text) {
  const m = text.match(/^\[(.+?)\]/);
  const url = m ? m[1] : '';
  return EXPECTED_NOISE_URLS.some((re) => re.test(url));
}

function shouldIgnore(text) {
  if (IGNORE_PATTERNS.some((re) => re.test(text))) return true;
  // 由 EXPECTED_AUTH_NOISE 列出的端点（已知 smoke token 不够强）
  return EXPECTED_AUTH_NOISE.some((p) => text.includes(p));
}

async function main() {
  if (!existsSync(OUT_DIR)) await mkdir(OUT_DIR, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 1440, height: 900 },
  });

  // 注入认证与首启状态，避免被引导/未登录态污染
  await context.addInitScript(
    ([adminToken]) => {
      window.localStorage.setItem('agent-gateway.apiKey', 'sk-demo-primary-0001');
      window.localStorage.setItem('agent-gateway.tenant', 'primary');
      window.localStorage.setItem('agent-gateway.adminToken', adminToken);
      window.localStorage.setItem(
        'agent-gateway.onboarding',
        JSON.stringify({ status: 'completed', doneSteps: [0, 1, 2], current: 0, skipped: true }),
      );
    },
    [SMOKE_ADMIN_TOKEN],
  );

  const results = [];

  for (const route of ROUTES) {
    const page = await context.newPage();
    const consoleErrors = [];
    const consoleWarnings = [];
    const pageErrors = [];
    const failedRequests = [];
    const expectedNoise = [];

    page.on('console', (msg) => {
      const type = msg.type();
      const text = msg.text();
      const loc = msg.location();
      const url = loc?.url ? loc.url.replace(/^http:\/\/[^/]+/, '') : 'unknown';
      const entry = `[${url}] ${text}`;
      const noise = shouldIgnore(text) || isExpectedNoise(entry);
      if (type === 'error') {
        if (!noise) consoleErrors.push(entry);
        else expectedNoise.push({ url, text });
      } else if (type === 'warning') {
        if (!noise) consoleWarnings.push(entry);
      }
    });
    page.on('pageerror', (err) => pageErrors.push(err.message));
    page.on('requestfailed', (req) => {
      const errText = req.failure()?.errorText ?? 'unknown';
      // host-level transient noise: VPN/wifi adapter handover, momentary offline.
      // 已 try-catch 降级的页面不应被打成"页面错误"，仅记到 expectedNoise。
      if (/^net::ERR_(NETWORK_CHANGED|INTERNET_DISCONNECTED|ABORTED)$/.test(errText)) {
        expectedNoise.push({ url: req.url(), text: `network: ${errText}` });
        return;
      }
      failedRequests.push({ url: req.url(), failure: errText, method: req.method() });
    });
    page.on('response', (resp) => {
      if (resp.status() >= 500) {
        failedRequests.push({
          url: resp.url(),
          status: resp.status(),
          method: resp.request().method(),
        });
      }
    });

    const safeName = route.replace(/[^a-z0-9]+/gi, '_') || 'root';
    const screenshotPath = path.join(OUT_DIR, `${safeName}.png`);

    let status = 'ok';
    let statusCode = 0;
    let navError = null;
    let errBoundaryVisible = false;
    let mainVisible = false;

    // Vite dev server cold compile can take >20s on first hit; retry once on timeout.
    let navAttempts = 0;
    while (navAttempts < 2) {
      try {
        const resp = await page.goto(`${BASE_URL}${route}`, {
          waitUntil: 'domcontentloaded',
          timeout: 45_000,
        });
        statusCode = resp ? resp.status() : 0;
        if (statusCode >= 400) status = 'http-error';
        break;
      } catch (err) {
        navAttempts++;
        navError = err.message;
        if (navAttempts >= 2) {
          status = 'nav-error';
          break;
        }
        // 等待 2s 再重试一次（让 Vite 完成 cold compile）
        await page.waitForTimeout(2_000);
      }
    }

    if (status !== 'nav-error') {
      try {
        await page.locator('main').first().waitFor({ timeout: 25_000 });
        mainVisible = true;
      } catch {
        mainVisible = false;
        status = 'blank';
      }

      await page.waitForTimeout(1_500);

      errBoundaryVisible = await page
        .getByText(/页面出错了|Something went wrong/i)
        .count()
        .then((c) => c > 0)
        .catch(() => false);

      try {
        await page.screenshot({ path: screenshotPath, fullPage: false });
      } catch {
        /* screenshot best-effort */
      }
    }

    if (errBoundaryVisible) status = 'error-boundary';
    if (consoleErrors.length > 0 || pageErrors.length > 0) {
      if (status === 'ok') status = 'console-error';
    }

    results.push({
      route,
      status,
      statusCode,
      mainVisible,
      errBoundaryVisible,
      navError,
      consoleErrors,
      consoleWarnings,
      pageErrors,
      failedRequests,
      expectedNoise,
      screenshot: path.relative(process.cwd(), screenshotPath),
    });

    const tag = status === 'ok' ? '✅' : status === 'blank' ? '⚠️' : '❌';
    console.log(
      `${tag} ${route.padEnd(22)} status=${status} http=${statusCode} ` +
        `errs=${consoleErrors.length} pageErr=${pageErrors.length} ` +
        `netFail=${failedRequests.length} boundary=${errBoundaryVisible}`,
    );
    await page.close();
  }

  await browser.close();

  // 写报告
  await writeFile(REPORT_FILE, JSON.stringify(results, null, 2));

  // 写 Markdown 汇总
  const failed = results.filter((r) => r.status !== 'ok');
  const lines = [];
  lines.push(`# 全路由巡检报告 — ${new Date().toISOString()}`);
  lines.push('');
  lines.push(`共 **${results.length}** 个路由，正常 **${results.length - failed.length}**，异常 **${failed.length}**`);
  lines.push('');
  lines.push('| Route | Status | HTTP | Console Err | Page Err | Net Fail | Boundary |');
  lines.push('|---|---|---|---|---|---|---|');
  for (const r of results) {
    lines.push(
      `| ${r.route} | ${r.status} | ${r.statusCode} | ${r.consoleErrors.length} | ${r.pageErrors.length} | ${r.failedRequests.length} | ${r.errBoundaryVisible ? 'Y' : ''} |`,
    );
  }
  if (failed.length) {
    lines.push('');
    lines.push('## 失败明细');
    for (const r of failed) {
      lines.push(`### ${r.route} — ${r.status}`);
      if (r.navError) lines.push(`- navError: \`${r.navError}\``);
      if (r.consoleErrors.length) {
        lines.push('- console.error:');
        r.consoleErrors.forEach((e) => lines.push(`  - ${e}`));
      }
      if (r.pageErrors.length) {
        lines.push('- pageerror:');
        r.pageErrors.forEach((e) => lines.push(`  - ${e}`));
      }
      if (r.failedRequests.length) {
        lines.push('- failedRequests:');
        r.failedRequests.forEach((f) =>
          lines.push(`  - ${f.method} ${f.url} → ${f.failure ?? f.status}`),
        );
      }
    }
  }
  await writeFile(SUMMARY_FILE, lines.join('\n') + '\n');

  // 退出码
  const hasCritical = results.some(
    (r) =>
      r.pageErrors.length > 0 ||
      r.errBoundaryVisible ||
      r.status === 'error-boundary' ||
      r.status === 'nav-error' ||
      r.status === 'blank' ||
      r.statusCode >= 500 ||
      r.consoleErrors.some((e) => /TypeError|ReferenceError|Uncaught|SyntaxError|is not a function/i.test(e)),
  );
  process.exit(hasCritical ? 1 : 0);
}

main().catch((e) => {
  console.error('巡检脚本异常:', e);
  process.exit(2);
});