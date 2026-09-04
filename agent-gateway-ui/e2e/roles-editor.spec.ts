import { test, expect } from '@playwright/test';

/**
 * 角色编辑器专项验证（roles-editor.spec.ts）
 * 核心断言：编辑弹窗回显非空白（曾因 antd destroyOnClose 时序坑全空白）
 *           + 三型权限（Agent/模型/技能）编辑与回显往返一致
 */
const API_KEY = process.env.E2E_API_KEY ?? 'sk-demo-primary-0001';
const TENANT = process.env.E2E_TENANT ?? 'primary';

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

test('编辑弹窗回显：名称/描述/三型权限全部非空白', async ({ page }) => {
  await page.goto('/roles');
  // 打开「平台管理员」编辑（该角色种子含 Agent+Agent+Model 三条权限）
  await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });
  await page.locator('tr', { hasText: '平台管理员' })
    .getByRole('button', { name: /编\s*辑/ }).click();

  // 断言：名称输入框回显种子值（修复前这里是空白）
  await expect(page.getByLabel('名称')).toHaveValue('平台管理员', { timeout: 10_000 });
  await expect(page.getByLabel('描述')).toHaveValue('全量权限（种子数据）');

  // 断言：权限行回显 3 行（Agent 下拉选中值 + 技能 tags + 模型 chips）
  const agentSelects = page.locator('.ant-select-selection-item', { hasText: 'echo-agent' });
  await expect(agentSelects.first()).toBeVisible({ timeout: 10_000 });
  // 技能白名单回显为 tags（run / debug chips）
  await expect(page.locator('.ant-select-selection-item-content', { hasText: 'run' }).first()).toBeVisible();
  await expect(page.locator('.ant-select-selection-item-content', { hasText: 'debug' }).first()).toBeVisible();
  // 模型行：多选框内显示种子模型 chips
  await expect(page.locator('.ant-select-selection-item-content', { hasText: 'deepseek-chat' }).first()).toBeVisible();

  // 取消关闭，不留脏状态
  await page.getByRole('button', { name: /取\s*消/ }).click();
});

test('编辑器操作：切换权限类型 + 增删权限行', async ({ page }) => {
  await page.goto('/roles');
  await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });

  // 新建角色：验证类型切换渲染对应控件
  await page.getByRole('button', { name: /新建角色/ }).click();
  await expect(page.getByLabel('名称')).toBeVisible({ timeout: 10_000 });

  // 默认一行 Agent 类型：Agent 下拉 + 技能 tags 输入存在
  await expect(page.locator('.ant-modal .ant-select').first()).toBeVisible();

  // 添加一行 → Segmented 切换为「模型」→ 出现模型多选框
  await page.getByRole('button', { name: /添加权限/ }).click();
  await page.locator('.ant-modal .ant-segmented').nth(1).getByText('模型').click();
  await expect(page.locator('.ant-select-selection-placeholder', { hasText: '模型列表' }))
    .toBeVisible({ timeout: 5_000 });

  // Agent 下拉结构化：点开第一行 Agent 选择器，选项来自注册表（echo-agent）
  await page.locator('.ant-modal .ant-select').first().click();
  const agentOpt = page.locator('.ant-select-dropdown:visible .ant-select-item-option',
    { hasText: 'echo-agent' });
  await expect(agentOpt).toBeVisible({ timeout: 5_000 });
  await page.keyboard.press('Escape');

  // 删除第二行 → 只剩一行
  await page.locator('.ant-modal button[aria-label="删除此条权限"]').nth(1).click();
  await expect(page.locator('.ant-modal button[aria-label="删除此条权限"]')).toHaveCount(1);

  await page.getByRole('button', { name: /取\s*消/ }).click();
});
