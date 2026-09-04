# Tasks: 运营台 X-Admin-Token 鉴权

- [x] **A.1** AdminTokenFilter（@Order(15)，/v1/admin/**，常数时间比较，401 GW-1401）
- [x] **A.2** application.yml 增加 gateway.security.admin-token（${GATEWAY_ADMIN_TOKEN:}，默认空=关闭）
- [x] **A.3** AdminTokenFilterTest 6 例（两态：配置/未配置）
- [x] **A.4** 前端 request.ts：getAdminToken/setAdminToken + /admin 路径自动附带 X-Admin-Token；clearAuth/401 不清除 adminToken
- [x] **A.5** Settings.tsx 新增 X-Admin-Token 表单项（保存/清除联动）
- [x] **A.6** request.test.ts +4 例
- [x] **B.1** 验证：AdminTokenFilterTest + RbacFilterTest 11 例全绿
- [x] **B.2** 验证：npx tsc --noEmit 通过；vitest 30 文件 207 例全绿
- [x] **B.3** 补录本 openspec 变更记录（事后归档）
