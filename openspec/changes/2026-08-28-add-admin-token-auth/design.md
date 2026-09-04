# Design: 运营台 X-Admin-Token 鉴权

## 1. 技术决策

| 项 | 选 | 理由 |
|---|---|---|
| Filter vs 拦截器 | 新 `OncePerRequestFilter`（@Component @Order(15)） | 与 RbacFilter 同模式；Order 15 先于 RbacFilter(20)，管理路径先做管理鉴权再做业务 RBAC 预检 |
| 配置注入 | 构造器 `@Value("${gateway.security.admin-token:}")` | 单值配置，无需 ConfigurationProperties；默认空串 = 关闭 |
| 开关语义 | 空/空白 → 全放行 | 向后兼容红线：未配置的现有部署行为 100% 不变 |
| 比较 | `MessageDigest.isEqual`（常数时间） | 防 token 逐字节时序侧信道 |
| 失败响应 | 401 + `{"error":"GW-1401: admin token missing or invalid"}` | 与 RbacFilter 403 JSON 风格一致；401（凭据缺失/错误）区别于 403（授权拒绝） |
| 前端发送范围 | 仅 `fullPath.startsWith('/admin')` | 管理凭据不泄露到业务端点请求 |
| 前端清除语义 | 401 自动 clearAuth 只清 apiKey/tenant，**不清 adminToken** | 业务 401（如演示 key 失效）不应锁死运营台 |

## 2. 与既有认证的关系

- `X-API-Key`：终端用户凭据，全请求注入，由既有认证层校验（缺失 401 兜底）
- `X-Admin-Token`：管理端独立凭据，仅 `/v1/admin/**` 校验/附带；两者互不替代、互不影响
- RbacFilter 不变：只管 `/v1/chat/{agent}`、`/v1/agents/{agent}/*` 的 Agent 级预检

## 3. 配置

```yaml
gateway:
  security:
    admin-token: ${GATEWAY_ADMIN_TOKEN:}   # 空=关闭（默认）
```

## 4. 涉及文件

后端：`gateway-interfaces/.../interfaces/security/AdminTokenFilter.java`（新）+ `AdminTokenFilterTest.java`（新）+ `gateway-bootstrap application.yml`。
前端：`agent-gateway-ui/src/lib/request.ts`、`src/pages/Settings.tsx`、`tests/request.test.ts`。
