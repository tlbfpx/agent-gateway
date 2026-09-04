import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright 配置 — agent-gateway UI 端到端测试
 *
 * 前置：网关(8080) + UI dev(5173) 已启动（webServer 配置 reuseExistingServer，
 * 已在跑的服务会被复用，不会重复起）。
 * 浏览器：PLAYWRIGHT_BROWSERS_PATH=./.pw-browsers npx playwright install chromium
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // 单 worker 串行：预算 CRUD 有共享租户状态，避免竞态
  workers: 1,
  retries: process.env.CI ? 2 : 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: [
    {
      // UI dev server（已在跑则复用）
      command: 'npm run dev -- --host 0.0.0.0 --port 5173',
      url: 'http://localhost:5173',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
});
