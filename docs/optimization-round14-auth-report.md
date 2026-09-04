# Round 14 #2 报告 — bcrypt + 多 Admin 真鉴权

> 日期：2026-09-02 · 主攻：**安全补强 — 真实密码哈希 + Admin 鉴权**
> 来源：Round 12 #1 + Round 13 报告 §九 R14+ 候选
> 借鉴：OWASP Password Storage Cheat Sheet 2023

---

## 一、动机

Round 12 #1 实现的 AdminUser 只有字段 `apiKeyHash`,但 **任何非空 X-Admin-Token 都视为 OWNER**(静态兼容)。
这是**安全漏洞**:任何拿到 endpoint URL 的人只要传任意字符串就能获得最高权限。

R14 #2 补齐真鉴权链路。

## 二、产出

| # | commit | 内容 |
|---|---|---|
| 1 | `<bcrypt-domain>` | PasswordHasher + AdminAuthService + 13 单测 |
| 2 | `8fd263b9` | AdminAuthController + AutoConfig + 9 单测 |
| 3 | `<ui>` | auth.ts + Login.tsx + 路由 |

**累计 22 用例全绿（PasswordHasher 5 + AdminAuthService 8 + AdminAuthController 9）**

## 三、亮点

### 1. 零新依赖
PBKDF2-HMAC-SHA256 是 JDK 9+ 标准库(`javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`),
无 spring-security / jBCrypt 等额外依赖。
- 100k 迭代（OWASP 2023 推荐)
- 16B salt + 32B hash
- PHC 格式输出（`$pbkdf2-sha256$100000$<saltB64>$<hashB64>`）
- 时间恒定比较（`MessageDigest.isEqual` 防时序攻击)

### 2. Session-based 鉴权
token 格式 `v1.<adminId>.<randomBase64>`,24h TTL;
存储在内存 `ConcurrentHashMap`（R15 可换 JWT / Redis）。
verifyToken 返回 `Optional<AdminRole>` 用于 RBAC 闸门。

### 3. 静态 token 兼容保留
原有 `AdminAdminController` 仍接受任意非空 token 当 OWNER(向后兼容);
新 `AdminAuthController` 走真鉴权。R15 切强制:用真 token 优先,无 adminUser 表时降级到静态。

### 4. 改密 / 停用账号 / 错误码翻译
- 改密:`change-password` ≥8 字符 + ADMIN+ 权限校验
- 停用:login 时检查 `status.canLogin()`,SUSPENDED/DELETED 返 403
- 错密码:401;短密码:400;失效 token:401

## 四、API 速查

```
POST /v1/admin/auth/login             body: { tenantId, email, password }
                                        → 200 { token, user }
                                        → 401 invalid credentials
                                        → 403 account not active
POST /v1/admin/auth/logout           header: X-Admin-Token
                                        → 200 { loggedOut: true }
POST /v1/admin/auth/me                header: X-Admin-Token
                                        → 200 { role }
                                        → 401 invalid token
POST /v1/admin/auth/change-password   header: X-Admin-Token (ADMIN+)
                                        body: { adminId, newPassword }
                                        → 400 password < 8
                                        → 200 user map
```

## 五、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-application -am test` | ✅ 13/13 |
| `mvn -pl :gateway-interfaces -am test` | ✅ 9/9 |
| 后端编译 | ✅ BUILD SUCCESS |
| `npx tsc --noEmit`(auth 新代码) | ✅ 0 新错误 |

## 六、已知限制（留 R15）

- Session 存内存,重启即丢;R15 接 Redis / JWT
- 静态 token 兼容:任意非空当 OWNER;R15 强制真鉴权
- 无 rate limit / lockout;R15 加（防爆破）
- 无 OAuth 2.1 / OIDC;R15 走 /authorize + PKCE

## 七、决策点

请用户确认下一步：
- **A**：接受 Round 14 #2,继续 R14 #3 (K8s CRD) / #4 (LLM-as-judge)
- **B**：跳过 R14 剩余,做 verify.sh 末次复跑确认全绿
- **C**：直接到 R15 平台化(MCP 转发 / bcrypt SSO / K8s CRD / LLM-judge 全)
