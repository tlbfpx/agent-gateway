# Design: 基础骨架搭建（add-foundation-skeleton）

> 本 change 特有的技术决策。详细实现 step 见 `docs/superpowers/plans/2026-08-12-foundation.md`。

## 架构决策

### 1. 洋葱/六边形架构与 11 模块依赖方向

依据 spec §3.2，依赖方向严格单向：

```
interfaces / bootstrap
       │
       ▼
application ──▶ domain
       │
       ▼
infra-* （实现 domain 定义的端口）
```

**关键约束**：
- `application` 不得依赖 `infra`/`interfaces`/`api`/`bootstrap`（负向断言验证）。
- `infra-a2a` 不得依赖 `application`/`interfaces`/其他 `infra-*`。
- `bootstrap` 可依赖 `interfaces`（启动链路验证）。

详见 spec §3.2 完整模块定义。

### 2. domain 严格零框架（关键修正）

**问题**：spec §3.3 的 `AgentCard` 用了 `JsonNode`（Jackson），与零框架冲突；端口用了 `Flux`（Reactor），与零框架冲突。

**决策**（本 change 锁定，已同步修正 spec §3.3）：
- **schema 字段**：`JsonNode inputSchema/outputSchema` → `String`（JSON 文本）。解析为 JsonNode 的工作在 `gateway-api`/infra 层完成。
- **流式抽象**：`Flux<ToolEvent>` → `java.util.concurrent.Flow.Publisher<ToolEvent>`（JDK 9+ reactive，零依赖）。infra 实现时需编写 Flow ↔ Reactor Flux 适配器。
- **ChatClient 抽象**：domain 不依赖 Spring AI `ChatClient`，定义 `LlmSession` 接口，由 `gateway-infra-llm` 桥接。

**影响**：后续「gateway-infra-llm」计划必须实现 Flow↔Flux 适配器（见 Plan ① 的执行交接）。

### 3. 0 阶段 Spike 范围

**聚焦**：本 change 只验证 **LLM starter 装配**（dashscope 为代表）。

**暂不验证**：Nacos A2A 客户端兼容性（依赖 A2A 计划的依赖坐标）。Nacos A2A 验证留到「A2A + Nacos 发现」计划的前置 Spike，避免起 Nacos 却不用的死代码。

### 4. JaCoCo 覆盖率门禁

依据 spec §9.1，`gateway-domain` 强制行覆盖 ≥ 90%：

```xml
<rule><element>BUNDLE</element><limits>
  <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.90</minimum></limit>
</limits></rule>
```

验证：`mvn -pl gateway-domain test` 自动触发 jacoco check。

### 5. 依赖方向负向断言

强制执行 spec §3.2 依赖方向（用负向断言，而非正向 grep）：

```bash
# application 不得依赖 infra/interfaces/api/bootstrap
mvn -q -pl gateway-application dependency:tree | grep -E 'gateway-(infra|interfaces|api|bootstrap)'
# Expected: 无输出（exit=1）
```

## 关键类型定义（索引）

| 类型 | 模块 | 职责 |
|------|------|------|
| `UserId`/`TenantId`/`ModelId`/`SessionId`/`RoleId`/`ApiKeyId`/`AgentVersion` | domain.shared | Identity 值对象 |
| `Session`/`Message`/`ContextWindow` | domain.session | 会话聚合根、消息 sealed interface、Token 裁剪 |
| `AgentCard` | domain.registry | A2A AgentCard 领域视图（schema 用 String） |
| `ModelDef`/`Capability` | domain.model | 模型定义（spec §17.2 权威） |
| `AuthPrincipal`/`AgentGrant` | domain.iam | 认证主体、Agent 级 + 模型级 RBAC 判定 |
| `ToolPort`/`AgentCardPort`/`ChatClientPort`/`LlmSession` | domain.orchestration | 出站端口（JDK Flow，零框架） |
| `ToolEvent`/`LlmEvent`/`InvocationCtx`/`ToolDescriptor` | domain.orchestration | 流式事件、调用上下文、工具描述 |

详细实现见 `docs/superpowers/plans/2026-08-12-foundation.md` Chunk 2（Task 5-9）。

## 验证门禁

| 门禁 | 命令/方式 | 期望 |
|------|-----------|------|
| 全量编译 + 测试 | `mvn clean test` | BUILD SUCCESS |
| domain 覆盖率 | `mvn -pl gateway-domain test` | jacoco check ≥ 90% |
| domain 零框架 | `mvn -pl gateway-domain dependency:tree` | 无 Spring/Jackson |
| 启动验证 | `mvn -pl gateway-bootstrap spring-boot:run` | 监听 8080 |
| 依赖方向 | 见 §5 | 负向断言无输出 |
