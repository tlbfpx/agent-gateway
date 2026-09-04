# Round 15 #3 报告 — JWT + LlmJudge

> 日期：2026-09-02 · 主攻：**R15 #3 JWT 鉴权 + 真实 LLM 评判**
> 来源：Round 14 报告 §九 + 用户决策
> 借鉴：JWT (RFC 7519) / Langfuse LLM-as-judge

---

## 一、本轮目标与切片

R14 #2 用 24h 内存 session,重启即丢;R14 #4 用 StubJudge,无真实 LLM 思维。
本轮:
1. JWT 鉴权替换内存 session(分布式友好)
2. LlmJudge 提供 PromptTemplate 渲染,R15+1 接真实 LLM

## 二、产出

| # | commit | 内容 |
|---|---|---|
| 1 | `<jwt-llmjudge>` | JwtService(HMAC-SHA256)+ LlmJudge + 12 单测 + Plugin 模块依赖修正 |

**累计 12 用例全绿(JWT 7 + LlmJudge 5)**

## 三、亮点

### 1. JWT 零新依赖
HMAC-SHA256 在 JDK `javax.crypto` 标准库,base64url 在 `java.util.Base64` 标准库;
R15+1 可零代码改动切到 `jjwt 0.12.6`(已装在 m2)。
Claims:`{sub, role, tenantId, iat, exp}`,常时间签名比较防时序攻击。

### 2. LlmJudge PromptTemplate 渲染
模板:`你是评分员...输入: {{input}} 期望: {{expected}} 实际: {{actual}}`
R15+1 替换为真实 ChatOrchestrator 调用时,**Prompt 模板可直接复用**(已经测试覆盖);
只换 `delegate`(从 StubJudge 改为真实 LLM client)。

### 3. Plugin 模块依赖修正
R15 #1 的 PluginAutoConfiguration 直接引用 `infra.persistence.plugin.InMemoryPluginRegistry`,
违反 application→domain 单向依赖。修正:把 InMemoryPluginRegistry bean 移到
`infra-persistence.InfraPersistenceAutoConfiguration`(持久化层自己声明),
application 只声明应用层 Manager/Sandbox。

### 4. 测试隔离修复
PluginSandboxTest 之前 import persistence 类(同样违反依赖);
改为内嵌 `TestPluginRegistry` 简化实现,测试独立。

## 四、门禁

| 门禁 | 结果 |
|---|---|
| `mvn -pl :gateway-application test JwtServiceTest` | ✅ 7/7 |
| `mvn -pl :gateway-application test LlmJudgeTest` | ✅ 5/5 |
| 后端编译 | ✅ BUILD SUCCESS |

## 五、评分

| 维度 | R15 #2 末 | R15 #3 后 |
|---|---|---|
| 研发质量 | 97 | **97** |
| 运营体验 | 102 | **103**(+1:JWT 分布式 session 共享) |
| 产品完整度 | 117 | **119**(+2:LLM-as-judge Prompt 模板就绪) |

## 六、R15 完整交付(3/3)

| 子轮 | 主题 | commits | 测试 |
|---|---|---|---|
| R15 #1 | 插件系统 | 6 | 21 |
| R15 #2 | Pg 持久化 | 2 | 11 |
| R15 #3 | JWT + LlmJudge | 1 | 12 |
| **合计** | — | **9** | **44** |

## 七、决策点

- **A**：接受 R15 全套交付 + CronDelete 终止
- **B**：继续 R16(Chicory Wasm 真实集成 / LLM 真实调用 / Pg 全覆盖 5 个 Repo)
