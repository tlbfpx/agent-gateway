import { test, expect, type Page } from '@playwright/test';

/**
 * agent-gateway UI 端到端测试（D1 RBAC + D2 计费/预算 主流程）
 *
 * 覆盖页面：
 *   - Dashboard（/dashboard）：壳加载 + 侧栏导航
 *   - 成本中心（/cost）：AdminMetrics 真实数据看板（GW-QUOTA-009）
 *   - 预算管理（/budgets）：D2 新页，预算 CRUD 全流程（GW-QUOTA-008）
 *
 * 选择器说明：本项目 PageHeader/Card 均为 div 渲染（无 heading role），
 * 故标题用 getByText；侧栏 antd menuitem 的 accessible name 含图标前缀，用正则匹配。
 *
 * 敏感信息：API Key / 租户从环境变量读取（E2E_API_KEY / E2E_TENANT）。
 * TODO: 演示 key sk-demo-primary-0001 仅本地 dev 可用；生产环境请设置
 *       E2E_API_KEY 为真实签发 key，并确保对应租户有测试数据权限。
 */
const API_KEY = process.env.E2E_API_KEY ?? 'sk-demo-primary-0001';
const TENANT = process.env.E2E_TENANT ?? 'primary';

/** 向 localStorage 注入认证信息（addInitScript 在每次导航前执行，无竞态）。 */
async function login(page: Page) {
  await page.addInitScript(
    ([key, tenant]) => {
      window.localStorage.setItem('agent-gateway.apiKey', key!);
      window.localStorage.setItem('agent-gateway.tenant', tenant!);
    },
    [API_KEY, TENANT],
  );
}

test.beforeEach(async ({ page }) => {
  // 每个用例前置：注入认证 + 标记首启引导已完成（避免 Onboarding 遮挡点击）
  await login(page);
  await page.addInitScript(() => {
    window.localStorage.setItem(
      'agent-gateway.onboarding',
      JSON.stringify({ status: 'completed', doneSteps: [0, 1, 2], current: 0, skipped: true }),
    );
  });
});

test.describe('壳与导航', () => {
  test('首页可加载并跳转 Dashboard', async ({ page }) => {
    await page.goto('/');
    // 断言：未知路径自动重定向到 /dashboard，页面主标题「系统运行态势」出现
    await expect(page).toHaveURL(/\/dashboard/);
    // Dashboard 顶部为 HeroBanner（非 PageHeader），按可见文本断言主标题
    await expect(page.getByText('系统运行态势', { exact: true }).first()).toBeVisible();
  });

  test('侧栏可导航到成本中心', async ({ page }) => {
    await page.goto('/dashboard');
    // 点击侧栏「成本中心」菜单项（accessible name 含图标前缀 "dollar 成本中心"，用正则匹配）
    await page.getByRole('menuitem', { name: /成本中心/ }).click();
    // 断言：URL 与页面标题同步切换
    await expect(page).toHaveURL(/\/cost/);
    await expect(page.locator('.page-title', { hasText: '成本中心' })).toBeVisible();
  });
});

test.describe('成本中心（D2 GW-QUOTA-009）', () => {
  test('成本看板加载并展示维度切片', async ({ page }) => {
    await page.goto('/cost');
    // 等待页面标题渲染（数据加载完成后 Tabs 才挂载）
    await expect(page.locator('.page-title', { hasText: '成本中心' })).toBeVisible();

    // 断言：维度 Tab 至少 1 个可见（租户/Key/模型/日期）
    // TODO: 若后端 /admin/metrics/cost 未接线或无审计数据，页面走前端聚合降级，
    //       断言仅验证结构而非具体金额；接入真实计费后可加数值断言。
    const tabs = page.getByRole('tab');
    await expect(tabs.first()).toBeVisible();
    expect(await tabs.count()).toBeGreaterThanOrEqual(1);
  });
});

test.describe('预算管理（D2 GW-QUOTA-008，CRUD 全流程）', () => {
  // 「当前预算」卡片：antd Card head 内含标题文本「当前预算」
  const budgetCard = (page: Page) =>
    page.locator('.ant-card', { has: page.getByText('当前预算', { exact: true }) });

  test('创建 → 查看 → 更新 → 删除预算', async ({ page }) => {
    test.setTimeout(90_000);
    // 前置：经 API 直删残留预算，保证按钮处于「创建」态（比 UI 删除更确定，无竞态）
    await page.request.delete('/v1/admin/billing/budgets', {
      headers: { 'X-API-Key': API_KEY, 'X-Tenant-Id': TENANT },
    });

    await page.goto('/budgets');
    await expect(page.locator('.page-title', { hasText: '预算管理' })).toBeVisible();

    // ---------- 1. 创建预算（日 50 / 月 100 / 阈值 80%） ----------
    // antd Form.Item label 与 InputNumber 关联，getByLabel 可定位
    await page.getByLabel('日上限').fill('50');
    await page.getByLabel('月上限').fill('100');
    await page.getByLabel('告警阈值 %').fill('80');
    await page.getByRole('button', { name: /创\s*建/ }).click();

    // 断言：创建成功后「当前预算」卡片展示日/月上限与阈值（自动重试等待刷新）
    const card = budgetCard(page);
    // 注意「50」同时命中 <strong>50</strong> 与「日用量 0 / 50」，用 .first() 消除 strict 冲突
    await expect(card.getByText('50').first()).toBeVisible({ timeout: 15_000 });
    await expect(card.getByText('100').first()).toBeVisible();
    await expect(card.getByText(/80%/)).toBeVisible();

    // ---------- 2. 更新预算（日上限 50 → 80） ----------
    await page.getByLabel('日上限').fill('80');
    await page.getByRole('button', { name: /更\s*新/ }).click();
    // 自动重试断言等待刷新后新值出现
    await expect(card.getByText('80').first()).toBeVisible({ timeout: 15_000 });

    // ---------- 3. 删除预算（Popconfirm 二次确认） ----------
    await page.getByRole('button', { name: /删\s*除/ }).click();
    await page.getByRole('button', { name: /确\s*定|OK/ }).click();
    // 断言：删除后回到「未配置预算」空态
    await expect(page.getByText('未配置预算')).toBeVisible({ timeout: 15_000 });
  });

  test('日上限 > 月上限时后端拒绝（GW-4302）并提示错误', async ({ page }) => {
    await page.goto('/budgets');
    await expect(page.locator('.page-title', { hasText: '预算管理' })).toBeVisible();

    // 构造冲突配置：日 200 > 月 100 → 后端 400 GW-4302
    await page.getByLabel('日上限').fill('200');
    await page.getByLabel('月上限').fill('100');
    await page.getByRole('button', { name: /创\s*建|更\s*新/ }).click();

    // 断言：antd message 错误提示包含 GW-4302 错误码（自动等待 toast 出现）
    await expect(page.getByText(/GW-4302/).first()).toBeVisible({ timeout: 15_000 });
  });

  test('预算页展示最近用量记账表', async ({ page }) => {
    await page.goto('/budgets');
    // 断言：记账表格卡片存在（表头列含「模型」「成本 (CNY)」）
    const tableCard = page.locator('.ant-card', { has: page.getByText('最近用量记账') });
    await expect(tableCard.getByRole('columnheader', { name: '模型' })).toBeVisible();
    await expect(tableCard.getByRole('columnheader', { name: /成本 \(CNY\)/ })).toBeVisible();
    // TODO: 表格数据依赖真实 LLM 调用产生 UsageRecord；无数据时为空表，
    //       接入真实流量后可断言行数 > 0。
  });
});

test.describe('后端 API 直连冒烟（经 Vite 代理）', () => {
  test('billing costs / budgets 接口可达', async ({ request }) => {
    // 使用 Playwright APIRequestContext 直连（走 5173 代理到 8080，带认证头）
    const headers = { 'X-API-Key': API_KEY, 'X-Tenant-Id': TENANT };

    // GET /v1/admin/billing/costs —— 200 + JSON 数组
    const costs = await request.get('/v1/admin/billing/costs', {
      params: { from: '2020-01-01T00:00:00Z', to: '2099-01-01T00:00:00Z' },
      headers,
    });
    expect(costs.status()).toBe(200);
    expect(Array.isArray(await costs.json())).toBeTruthy();

    // GET /v1/admin/billing/budgets —— 200（无预算时 204/空）
    const budgets = await request.get('/v1/admin/billing/budgets', { headers });
    expect([200, 204]).toContain(budgets.status());

    // TODO: 认证失败（401/403）场景需真实多租户 key 矩阵，当前演示 key 全放行。
  });
});
