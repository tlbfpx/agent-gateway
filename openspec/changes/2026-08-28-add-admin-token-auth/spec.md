# Spec: 运营台 X-Admin-Token 鉴权（可测试条款）

#### GW-ADMINTOKEN-001 配置开关
**MUST**：`gateway.security.admin-token` 为空或空白时，AdminTokenFilter 对所有请求放行（含 /v1/admin/**），行为与未引入本 Filter 完全一致。
**测试**：AdminTokenFilterTest.tokenEmpty_allRequestsPassThrough_disabledByDefault / blankConfigToken_treatedAsDisabled。

#### GW-ADMINTOKEN-002 管理 token 校验
**MUST**：token 非空时，`/v1/admin/**` 请求携带的 `X-Admin-Token` 与配置值匹配才放行；缺失或不匹配返回 401 且不进入 FilterChain，body 为 JSON 错误（GW-1401）。
**测试**：tokenConfigured_correctHeader_passesThrough / wrongHeader_returns401_andBlocksChain / missingHeader_returns401。

#### GW-ADMINTOKEN-003 路径范围
**MUST**：非 `/v1/admin/**` 路径（如 /v1/chat/**）不做管理鉴权，即使 token 已配置。
**测试**：tokenConfigured_nonAdminPath_passesThrough。

#### GW-ADMINTOKEN-004 与 X-API-Key 独立
**MUST**：前端 adminToken 与 apiKey 分开存储（localStorage `agent-gateway.adminToken`）；`X-Admin-Token` 仅对 /admin 前缀路径附带；401 自动清除（clearAuth）不清除 adminToken。
**测试**：request.test.ts adminToken 三态 / clearAuth 不清除。

#### GW-ADMINTOKEN-005 Settings 配置入口
**MUST**：Settings 页提供 X-Admin-Token 表单项，保存写入 localStorage、清除凭据按钮一并清除；留空 = 不发送该头。
**测试**：request.test.ts adminToken 未配置时不发送头（UI 联动人工验证）。
