// vite.config.ts
import { defineConfig } from "file:///Users/muxi/workspace/agent-gateway/agent-gateway-ui/node_modules/vite/dist/node/index.js";
import react from "file:///Users/muxi/workspace/agent-gateway/agent-gateway-ui/node_modules/@vitejs/plugin-react/dist/index.js";
var vite_config_default = defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/v1": {
        target: "http://localhost:8080",
        changeOrigin: true
      },
      // Actuator 指标（prompt_cache_hit_total / prompt_cache_miss_total 等）
      "/actuator": {
        target: "http://localhost:8080",
        changeOrigin: true
      }
    }
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    css: true,
    /** Playwright e2e 目录不归 vitest 管（npm run e2e 单独跑） */
    exclude: ["**/node_modules/**", "**/dist/**", "e2e/**", "playwright.config.ts"],
    /** 全局测试超时：antd Modal/Table 渲染较慢，默认 5s 偏紧 */
    testTimeout: 3e4,
    hookTimeout: 3e4
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcudHMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCIvVXNlcnMvbXV4aS93b3Jrc3BhY2UvYWdlbnQtZ2F0ZXdheS9hZ2VudC1nYXRld2F5LXVpXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ZpbGVuYW1lID0gXCIvVXNlcnMvbXV4aS93b3Jrc3BhY2UvYWdlbnQtZ2F0ZXdheS9hZ2VudC1nYXRld2F5LXVpL3ZpdGUuY29uZmlnLnRzXCI7Y29uc3QgX192aXRlX2luamVjdGVkX29yaWdpbmFsX2ltcG9ydF9tZXRhX3VybCA9IFwiZmlsZTovLy9Vc2Vycy9tdXhpL3dvcmtzcGFjZS9hZ2VudC1nYXRld2F5L2FnZW50LWdhdGV3YXktdWkvdml0ZS5jb25maWcudHNcIjsvLy8gPHJlZmVyZW5jZSB0eXBlcz1cInZpdGVzdFwiIC8+XG5pbXBvcnQgeyBkZWZpbmVDb25maWcgfSBmcm9tICd2aXRlJztcbmltcG9ydCByZWFjdCBmcm9tICdAdml0ZWpzL3BsdWdpbi1yZWFjdCc7XG5cbmV4cG9ydCBkZWZhdWx0IGRlZmluZUNvbmZpZyh7XG4gIHBsdWdpbnM6IFtyZWFjdCgpXSxcbiAgc2VydmVyOiB7XG4gICAgcG9ydDogNTE3MyxcbiAgICBwcm94eToge1xuICAgICAgJy92MSc6IHtcbiAgICAgICAgdGFyZ2V0OiAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJyxcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgfSxcbiAgICAgIC8vIEFjdHVhdG9yIFx1NjMwN1x1NjgwN1x1RkYwOHByb21wdF9jYWNoZV9oaXRfdG90YWwgLyBwcm9tcHRfY2FjaGVfbWlzc190b3RhbCBcdTdCNDlcdUZGMDlcbiAgICAgICcvYWN0dWF0b3InOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCcsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgIH0sXG4gICAgfSxcbiAgfSxcbiAgdGVzdDoge1xuICAgIGdsb2JhbHM6IHRydWUsXG4gICAgZW52aXJvbm1lbnQ6ICdqc2RvbScsXG4gICAgc2V0dXBGaWxlczogWycuL3Rlc3RzL3NldHVwLnRzJ10sXG4gICAgY3NzOiB0cnVlLFxuICAgIC8qKiBQbGF5d3JpZ2h0IGUyZSBcdTc2RUVcdTVGNTVcdTRFMERcdTVGNTIgdml0ZXN0IFx1N0JBMVx1RkYwOG5wbSBydW4gZTJlIFx1NTM1NVx1NzJFQ1x1OEREMVx1RkYwOSAqL1xuICAgIGV4Y2x1ZGU6IFsnKiovbm9kZV9tb2R1bGVzLyoqJywgJyoqL2Rpc3QvKionLCAnZTJlLyoqJywgJ3BsYXl3cmlnaHQuY29uZmlnLnRzJ10sXG4gICAgLyoqIFx1NTE2OFx1NUM0MFx1NkQ0Qlx1OEJENVx1OEQ4NVx1NjVGNlx1RkYxQWFudGQgTW9kYWwvVGFibGUgXHU2RTMyXHU2N0QzXHU4RjgzXHU2MTYyXHVGRjBDXHU5RUQ4XHU4QkE0IDVzIFx1NTA0Rlx1N0QyNyAqL1xuICAgIHRlc3RUaW1lb3V0OiAzMF8wMDAsXG4gICAgaG9va1RpbWVvdXQ6IDMwXzAwMCxcbiAgfSxcbn0pOyJdLAogICJtYXBwaW5ncyI6ICI7QUFDQSxTQUFTLG9CQUFvQjtBQUM3QixPQUFPLFdBQVc7QUFFbEIsSUFBTyxzQkFBUSxhQUFhO0FBQUEsRUFDMUIsU0FBUyxDQUFDLE1BQU0sQ0FBQztBQUFBLEVBQ2pCLFFBQVE7QUFBQSxJQUNOLE1BQU07QUFBQSxJQUNOLE9BQU87QUFBQSxNQUNMLE9BQU87QUFBQSxRQUNMLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxNQUNoQjtBQUFBO0FBQUEsTUFFQSxhQUFhO0FBQUEsUUFDWCxRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsTUFDaEI7QUFBQSxJQUNGO0FBQUEsRUFDRjtBQUFBLEVBQ0EsTUFBTTtBQUFBLElBQ0osU0FBUztBQUFBLElBQ1QsYUFBYTtBQUFBLElBQ2IsWUFBWSxDQUFDLGtCQUFrQjtBQUFBLElBQy9CLEtBQUs7QUFBQTtBQUFBLElBRUwsU0FBUyxDQUFDLHNCQUFzQixjQUFjLFVBQVUsc0JBQXNCO0FBQUE7QUFBQSxJQUU5RSxhQUFhO0FBQUEsSUFDYixhQUFhO0FBQUEsRUFDZjtBQUNGLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
