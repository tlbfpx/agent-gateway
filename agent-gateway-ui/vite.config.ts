/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // Actuator 指标（prompt_cache_hit_total / prompt_cache_miss_total 等）
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    css: true,
    /** Playwright e2e 目录不归 vitest 管（npm run e2e 单独跑） */
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**', 'playwright.config.ts'],
    /** 全局测试超时：antd Modal/Table 渲染较慢，默认 5s 偏紧 */
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});