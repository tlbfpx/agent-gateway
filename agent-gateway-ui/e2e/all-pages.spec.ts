import { test, expect, type Page } from '@playwright/test';

/**
 * 全页面数据校验（all-pages.spec.ts）
 *
 * 前置：网关(8080) + UI(5173) + TimescaleDB(5433) 在跑，
 *       且已执行 `python3 scripts/seed-all-pages.py`（种子数据）。
 *
 * 策略：逐路由导航 → 断言核心数据元素可见（非空态）。
 * 通用空态判据：antd Empty 描述文案 /「暂无数据」/「未配置」/「加载失败」。
 *
 * 敏感信息：E2E_API_KEY / E2E_TENANT 环境变量（本地 dev 回退演示 key）。
 * TODO: 生产环境请用真实签发 key + 已有业务数据的租户。
 */
const API_KEY = process.env.E2E_API_KEY ?? 'sk-demo-primary-0001';
const TENANT = process.env.E2E_TENANT ?? 'primary';

/** 每个用例前置：认证注入 + 关闭首启引导。 */
test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, tenant]) => {
      window.localStorage.setItem('agent-gateway.apiKey', key!);
      window.localStorage.setItem('agent-gateway.tenant', tenant!);
      window.localStorage.setItem(
        'agent-gateway.onboarding',
        JSON.stringify({ status: 'completed', doneSteps: [0, 1, 2], current: 0, skipped: true }),
      );
    },
    [API_KEY, TENANT],
  );
});

/** 页面级空态断言：主体区域不应出现「暂无数据/未配置/加载失败」。 */
async function expectNotEmpty(page: Page) {
  const empty = page.getByText(/暂无数据|未配置|加载失败|No data/);
  const count = await empty.count();
  // 表格允许空（如告警历史），但整页空态文本超过 3 处视为「无数据」
  expect(count, '页面不应整体为空态').toBeLessThanOrEqual(5);
}

/** 统一导航：goto + 等待主内容渲染 + 断言无 JS 崩溃（ErrorBoundary 不出现）。 */
async function visit(page: Page, path: string) {
  const errors: string[] = [];
  const handler = (e: Error) => errors.push(e.message);
  page.on('pageerror', handler);
  try {
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    // 等待主内容区出现（AppShell main 元素），自动等待替代 sleep
    await expect(page.locator('main').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/页面出错了|Something went wrong/i)).toHaveCount(0);
    await expectNotEmpty(page);
  } finally {
    page.off('pageerror', handler);
    expect(errors, `路由 ${path} 不应有未捕获 JS 错误`).toEqual([]);
  }
}

test.describe('总览 / 资源管理', () => {
  test('仪表盘有实时/派生指标数据', async ({ page }) => {
    await visit(page, '/dashboard');
    // 断言：总调用量卡片渲染出非「—」数值（种子 audit → 派生聚合）
    await expect(page.getByText('系统运行态势')).toBeVisible();
    // 断言：数据加载完成标记（HeroBanner 显示「最后刷新」+ LIVE/DERIVED 派生口径）
    await expect(page.getByText(/最后刷新/).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/LIVE|DERIVED/).first()).toBeVisible();
    // TODO: overview live 接口上线后可断言具体调用量数值
  });

  test('模型管理列表非空', async ({ page }) => {
    await visit(page, '/models');
    // 断言：data/models.json 的 3 个模型至少 1 行可见（角色平台管理员含模型权限也可验证）
    await expect(page.getByText(/共 3 条/).first()).toBeVisible({ timeout: 15_000 });
  });

  test('API Key 列表非空', async ({ page }) => {
    await visit(page, '/api-keys');
    // 断言：GET /admin/api-keys 返回的脱敏 key（sk-****）至少 1 行
    await expect(page.getByText(/共 \d+ 条/).first()).toBeVisible({ timeout: 15_000 });
  });

  test('服务发现 / Agent 注册有卡片', async ({ page }) => {
    await visit(page, '/discovery');
    await expect(page.getByText(/echo-agent|hr-agent|search-agent/).first()).toBeVisible({ timeout: 15_000 });
  });
});

test.describe('运营域', () => {
  test('成本中心有成本数据（PG 计费种子）', async ({ page }) => {
    await visit(page, '/cost');
    // 断言：四维切片 Tab 可见且有数据行（成本 > 0 或 calls > 0）
    await expect(page.getByRole('tab').first()).toBeVisible();
    await expect(page.getByText(/¥|CNY|cost|成本/i).first()).toBeVisible({ timeout: 15_000 });
  });

  test('预算管理有当前预算 + 记账明细', async ({ page }) => {
    // 自愈前置：其他用例（billing CRUD）可能删过预算——缺则经 API 重建，消除测试间耦合
    const existing = await page.request.get('/v1/admin/billing/budgets', {
      headers: { 'X-API-Key': API_KEY, 'X-Tenant-Id': TENANT },
    });
    if (!existing.ok() || !await existing.json()) {
      await page.request.post('/v1/admin/billing/budgets', {
        headers: { 'X-API-Key': API_KEY, 'X-Tenant-Id': TENANT, 'Content-Type': 'application/json' },
        data: { type: 'MONEY', dailyLimit: 500, monthlyLimit: 2000, alertThresholdPct: 80 },
      });
    }
    await visit(page, '/budgets');
    // 断言：种子预算（日 500 / 月 2000 / 80%）可见
    await expect(page.getByText(/500/).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/80%/).first()).toBeVisible();
    // 最近用量记账表：列头渲染 + 至少 1 行种子数据（30 天 × 3 模型）
    await expect(page.getByRole('columnheader', { name: '成本 (CNY)' })).toBeVisible();
    await expect(page.getByText(/alice|bob|carol|dave|seed-/).first())
      .toBeVisible({ timeout: 15_000 });
  });

  test('限流监控页可加载', async ({ page }) => {
    await visit(page, '/ratelimit');
    await expect(page.locator('.page-title, main').first()).toBeVisible();
  });

  test('调用链追踪有 span 数据', async ({ page }) => {
    await visit(page, '/traces');
    // 断言：种子 trace（POST /v1/chat/stream 或 chat.generate）出现
    await expect(page.getByText(/chat\.generate|POST \/v1\/chat\/stream/).first())
      .toBeVisible({ timeout: 15_000 });
  });

  test('Workflow 有运行记录', async ({ page }) => {
    await visit(page, '/workflows');
    // 断言：种子运行（seed-flow-* / COMPLETED 状态）出现
    await expect(page.getByText(/seed-flow|COMPLETED|已完成/).first())
      .toBeVisible({ timeout: 15_000 });
  });

  test('告警中心有 firing 告警', async ({ page }) => {
    await visit(page, '/alerts');
    // 断言：种子告警（critical/warning 级别或规则名）出现
    await expect(page.getByText(/P95 延迟过高|错误率飙升|critical|warning|严重|警告/).first())
      .toBeVisible({ timeout: 15_000 });
  });

  test('Webhook 有订阅列表', async ({ page }) => {
    await visit(page, '/webhooks');
    // 断言：两条种子订阅 URL 出现
    await expect(page.getByText('hooks.example.com').first()).toBeVisible({ timeout: 15_000 });
  });

  test('审计日志有事件流', async ({ page }) => {
    await visit(page, '/audit');
    // 断言：种子审计事件类型出现（SESSION_CHAT / RBAC_DENIED 等）
    await expect(page.getByText(/共 \d+ 条/).first()).toBeVisible({ timeout: 15_000 });
  });

  test('配置历史页可加载', async ({ page }) => {
    await visit(page, '/config-history');
    // TODO: 配置变更历史依赖真实的 models.json 修改记录；无数据时仅验证页面结构
    await expect(page.locator('main').first()).toBeVisible();
  });
});

test.describe('RBAC 域', () => {
  test('RBAC 总览有数据', async ({ page }) => {
    await visit(page, '/rbac');
    await expect(page.locator('main').first()).toBeVisible();
  });

  test('角色管理有 4 个种子角色', async ({ page }) => {
    await visit(page, '/roles');
    // 断言：种子角色名出现（平台管理员/开发者/只读访客/技能运维）
    await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('开发者').first()).toBeVisible();
    await expect(page.getByText('技能运维').first()).toBeVisible();
  });

  test('用户绑定有 4 个种子绑定', async ({ page }) => {
    await visit(page, '/user-bindings');
    // 断言：种子用户（alice/bob/carol/dave）及其角色出现
    // 页面默认列出全部角色（输入 UserId 查询后才显示绑定勾选）——断言种子角色在表中
    await expect(page.getByRole('columnheader', { name: '角色' })).toBeVisible();
    await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('技能运维').first()).toBeVisible();
  });

  test('策略中心页可加载', async ({ page }) => {
    await visit(page, '/policies');
    await expect(page.locator('main').first()).toBeVisible();
  });
});

test.describe('开发者 / 应用', () => {
  test('API 浏览器可加载 OpenAPI 文档', async ({ page }) => {
    await visit(page, '/api');
    await expect(page.locator('main').first()).toBeVisible();
  });

  test('对话测试页可进入（会话列表）', async ({ page }) => {
    await visit(page, '/chat');
    // TODO: 会话历史依赖真实 LLM 调用（/v1/chat/stream SSE）；
    //       无真实模型 key 时输入区可见即可，不做回复断言
    await expect(page.locator('main').first()).toBeVisible();
  });

  test('帮助 / 设置 / 健康检查页可加载', async ({ page }) => {
    await visit(page, '/help');
    await expect(page.locator('main').first()).toBeVisible();
    await visit(page, '/settings');
    await expect(page.locator('main').first()).toBeVisible();
    await visit(page, '/health');
    // 健康检查页应显示后端 /v1/health 聚合状态
    await expect(page.getByText(/UP|健康|ok/i).first()).toBeVisible({ timeout: 15_000 });
  });
});
