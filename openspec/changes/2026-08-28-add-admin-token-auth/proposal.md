# Proposal: 运营台管理端点鉴权 X-Admin-Token（add-admin-token-auth）

> **状态**：阶段四补录归档（实现已完成并全绿，本变更记录为事后补档）
> **来源**：P1 待办「运营台登录态」——/v1/admin/** 此前无独立管理凭据，仅依赖终端用户 X-API-Key 体系

## 动机

管理端点（/v1/admin/**：模型配置、API Key 签发、预算、RBAC 等）此前与业务端点共用同一认证层：
运营人员需要持有业务 API Key 才能进管理台，凭据边界不清；也无法单独轮换/吊销管理凭据。

## What

- 新增配置 `gateway.security.admin-token`（env `GATEWAY_ADMIN_TOKEN`），**默认空 = 关闭鉴权，向后兼容**（现有部署行为不变）
- 新增 `AdminTokenFilter`（@Order(15)，先于 RbacFilter@20）：token 非空且路径为 `/v1/admin/**` 时，要求请求头 `X-Admin-Token` 匹配，否则 401（错误码 GW-1401）；比较用 `MessageDigest.isEqual` 常数时间，防时序侧信道
- 前端 agent-gateway-ui：`lib/request.ts` 新增 adminToken 存取（localStorage `agent-gateway.adminToken`），仅对 `/admin` 路径自动附带 `X-Admin-Token` 头；Settings 页新增表单项（保存/清除联动）
- 与 X-API-Key 关系：两凭据相互独立。X-API-Key 仍按原逻辑注入所有请求；X-Admin-Token 只发管理路径。**401 / clearAuth 不清除 adminToken**（避免管理凭据被业务 401 误清，把运营台锁死）

## Non-goals

- 不引入完整登录态/session/JWT——留待运营台正式账号体系
- 不动 billing / ChatOrchestrator / Models 页面主体（其他任务范围）
- 不做 token 多租户化/按角色细分（单一共享管理 token，轻量方案）

## 验收

- 后端：AdminTokenFilterTest 6 例（正确头放行 / 错误头 401 / 缺失头 401 / 空配置放行 / 非 admin 路径放行 / 空白配置视为关闭）全绿
- 前端：request.test.ts 新增 4 例（存取 roundtrip / 仅 /admin 路径带头 / 未配置不带头 / clearAuth 不清除）全绿；`npx tsc --noEmit` 通过；vitest 30 文件 207 例全绿
