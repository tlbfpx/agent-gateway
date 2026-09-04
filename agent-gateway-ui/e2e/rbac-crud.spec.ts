import { test, expect } from '@playwright/test';

/**
 * RBAC 三页面 CRUD 全链路验证（rbac-crud.spec.ts）
 * 页面：/roles（角色管理）、/user-bindings（用户绑定）、/rbac（策略预览）
 * 前置：网关 + UI + 种子数据（scripts/seed-all-pages.py）
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

test('角色管理：新建 → 编辑 → 删除', async ({ page }) => {
  await page.goto('/roles');
  // 等表格渲染（种子角色出现）
  await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });
  const roleName = `e2e角色-${Date.now()}`;

  // --- 新建 ---
  await page.getByRole('button', { name: /新\s*建角色/ }).click();
  await page.getByLabel('名称').fill(roleName);
  await page.getByLabel('描述').fill('e2e CRUD 验证');
  // 新权限编辑器：Agent 下拉结构化选择（点开 → 选注册表中的 echo-agent）
  await page.locator('.ant-modal .ant-select').first().click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option',
    { hasText: 'echo-agent' }).first().click();
  await page.getByRole('button', { name: /保\s*存|确\s*定|OK/ }).click();
  await expect(page.getByText('角色已创建')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(roleName).first()).toBeVisible({ timeout: 10_000 });

  // --- 编辑（改名） ---
  const row = page.locator('tr', { hasText: roleName });
  await row.getByRole('button', { name: /编\s*辑/ }).click();
  await page.getByLabel('名称').fill(roleName + '-v2');
  await page.getByRole('button', { name: /保\s*存|确\s*定|OK/ }).click();
  await expect(page.getByText('角色已更新')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(roleName + '-v2').first()).toBeVisible({ timeout: 10_000 });

  // --- 删除 ---
  await page.locator('tr', { hasText: roleName + '-v2' })
    .getByRole('button', { name: /删\s*除/ }).click();
  await page.getByRole('button', { name: /保\s*存|确\s*定|OK/ }).click();
  await expect(page.getByText(/已删除/)).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText(roleName + '-v2')).toHaveCount(0, { timeout: 10_000 });
});

test('用户绑定：查询 → 勾选绑定 → 取消绑定', async ({ page }) => {
  await page.goto('/user-bindings');
  // 种子角色列表先渲染
  await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });

  const user = `e2e-user-${Date.now()}`;
  await page.getByRole('searchbox').fill(user);
  await page.getByRole('button', { name: /查\s*询/ }).click();
  // 等待该用户（无绑定）的角色表加载
  await expect(page.getByText('开发者').first()).toBeVisible({ timeout: 10_000 });

  // --- 勾选「开发者」绑定（click 而非 check：refresh 重渲染会触发 check 的重试竞态） ---
  const row = page.locator('tr', { hasText: '开发者' });
  await row.getByRole('checkbox').click();
  await expect(page.getByText(/已绑定 开发者/)).toBeVisible({ timeout: 10_000 });

  // --- 刷新后再查询：勾选态保持（真绑定持久化） ---
  await page.getByRole('button', { name: /查\s*询/ }).click();
  await expect(page.locator('tr', { hasText: '开发者' }).getByRole('checkbox')).toBeChecked({
    timeout: 10_000,
  });

  // --- 取消绑定（同理用 click） ---
  await page.locator('tr', { hasText: '开发者' }).getByRole('checkbox').click();
  await expect(page.getByText(/已解绑 开发者/)).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('tr', { hasText: '开发者' }).getByRole('checkbox')).not.toBeChecked({
    timeout: 10_000,
  });
});

test('用户绑定：预览权限返回可见结果', async ({ page }) => {
  await page.goto('/user-bindings');
  await expect(page.getByText('平台管理员').first()).toBeVisible({ timeout: 15_000 });

  // 查询种子用户 alice（已绑平台管理员角色）
  await page.getByRole('searchbox').fill('alice');
  await page.getByRole('button', { name: /查\s*询/ }).click();
  await expect(page.locator('tr', { hasText: '平台管理员' }).getByRole('checkbox')).toBeChecked({
    timeout: 10_000,
  });

  // 预览权限 → message 显示可用 Agent/模型
  await page.getByRole('button', { name: /预览权限/ }).click();
  await expect(page.getByText(/可用 Agent/).first()).toBeVisible({ timeout: 10_000 });
});

test('权限预览页：结构化表单模拟判定', async ({ page }) => {
  await page.goto('/rbac');
  // 目的说明 + 菜单改名后的页面标题
  await expect(page.getByText('权限自查')).toBeVisible();

  // 用户 ID 输入 + 资源全部下拉：Agent 类型默认 → 下拉选 echo-agent
  await page.getByLabel('用户 ID').fill('alice');
  await page.getByLabel('资源', { exact: true }).click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option',
    { hasText: 'echo-agent' }).first().click();
  await page.getByRole('button', { name: /开始判定/ }).click();

  // 判定结果：✓ 允许（alice 绑定平台管理员，含 echo-agent 权限）
  await expect(page.getByText('✓ 允许')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/echo-agent 在角色授权/)).toBeVisible();

  // 切换资源类型为「模型」：Segmented 点击 + 下拉选项联动切换为模型列表
  await page.locator('.ant-segmented-item', { hasText: '模型' }).click();
  await page.getByLabel('资源', { exact: true }).click();
  await expect(page.locator('.ant-select-dropdown:visible .ant-select-item-option').first())
    .toBeVisible({ timeout: 10_000 });
});
