# Tasks: 基础骨架搭建（add-foundation-skeleton）

> **详细 step（含完整代码、测试、命令）见 `docs/superpowers/plans/2026-08-12-foundation.md`**。本文件为任务清单视图，仅列出标题 + 目标 + 完成判据。
>
> 实现时遵循 `AGENTS.md`：每个 Task 可派一个 backend-developer 子代理执行（独立文件、TDD），修改同一文件的步骤串行。

## Chunk 1: Maven 多模块骨架 + 0 阶段 Spike

### Task 1: 初始化父 POM 与版本管理
- [ ] **目标**：建立父 pom（packaging=pom），统一管理 Java 21、Spring Boot 4.0、Spring AI 2.0.0-M1、SAA 2.0.0-M1 版本，声明 11 个子模块。
- [ ] **完成判据**：`pom.xml` 含 `<modules>` 列表（不含 example-agent），`git commit` 完成。

### Task 2: 0 阶段 Spike — Boot4 + SAA2.0-M1 兼容性验证
- [ ] **目标**：验证 Spring Boot 4.0 + SAA 2.0.0-M1 + dashscope starter 能编译装配，产出验证报告。
- [ ] **完成判据**：Spike 报告存在于 `docs/superpowers/spike/2026-08-12-saa-compat-report.md`，含 Artifact GAV 核对结果与验证矩阵；spike 模块从 pom `<modules>` 移除。

### Task 3: 创建所有空子模块（仅 pom，可编译）
- [ ] **目标**：创建 11 个模块的 pom + 目录骨架，`mvn compile` 全绿。
- [ ] **完成判据**：11 个 `pom.xml` 已创建，`mvn -q -DskipTests compile` BUILD SUCCESS。

### Task 4: 验证骨架可编译 + domain 零框架边界 + 测试工具链
- [ ] **目标**：验证 11 模块编译通过、domain 零框架（`dependency:tree` 无 Spring/Jackson）、测试工具链可用。
- [ ] **完成判据**：`mvn -q -pl gateway-domain dependency:tree` 输出无 Spring/Jackson，`mvn -q -pl gateway-domain test` BUILD SUCCESS。

## Chunk 2: gateway-domain 领域核心实现

### Task 5: shared 包 — 公共 Identity 值对象
- [ ] **目标**：定义 `UserId`/`TenantId`/`ModelId`/`SessionId`/`RoleId`/`ApiKeyId`/`AgentVersion`，配共享非空校验与单元测试。
- [ ] **完成判据**：`gateway-domain/src/main/java/com/company/agentgateway/domain/shared/` 全部 record 已创建，`IdentityTest` 全绿，`git commit` 完成。

### Task 6: session 包 — Session / Message / ContextWindow
- [ ] **目标**：实现 `Session` 聚合根（含 model 字段 + ToolResult 瘦身 spec §5.3）、`Message` sealed interface（4 种类型）、`ContextWindow`（Token 截断算法），配 TDD 测试。
- [ ] **完成判据**：`SessionTest`/`ContextWindowTest` 全绿，`git commit` 完成。

### Task 7: registry + model 包 — AgentCard / ModelDef
- [ ] **目标**：实现 `AgentCard`（schema 用 String）、`ModelDef`（spec §17.2 权威定义）、`Capability` 枚举，配单元测试。
- [ ] **完成判据**：`AgentCardTest`/`ModelDefTest` 全绿，`git commit` 完成。

### Task 8: iam 包 — AuthPrincipal / AgentGrant
- [ ] **目标**：实现 `AuthPrincipal`（Agent 级 + 模型级 RBAC 判定）、`AgentGrant`、`AuthChannel`，配单元测试。
- [ ] **完成判据**：`AuthPrincipalTest` 全绿，`git commit` 完成。

### Task 9: orchestration 包 — 出站端口（ToolPort / AgentCardPort / ChatClientPort）
- [ ] **目标**：定义 `ToolPort`/`AgentCardPort`/`ChatClientPort`（JDK Flow，零框架）、`LlmSession`、事件类型（`ToolEvent`/`LlmEvent`）、`InvocationCtx`、`ToolDescriptor`，配端口契约测试。
- [ ] **完成判据**：`PortContractTest` 全绿，`git commit` 完成。

### Task 10: JaCoCo 覆盖率门禁 + domain 全量测试 + spec §3.3 同步修订
- [ ] **目标**：domain pom 加 JaCoCo 插件（>90% 门禁），全量测试通过，同步修订 spec §3.3（`JsonNode`→`String`，`Flux`→`Flow`，补 `LlmSession` 定义）。
- [ ] **完成判据**：`mvn -q -pl gateway-domain test` 覆盖率 ≥90%，spec §3.3 已修订，`git commit` 完成。

## Chunk 3: bootstrap 启动 + 整体集成验证

### Task 11: gateway-bootstrap 启动类 + application.yml
- [ ] **目标**：创建 `GatewayApplication` 启动类（`scanBasePackages` 排除 domain）、`application.yml`、`@SpringBootTest` 测试。
- [ ] **完成判据**：`GatewayApplicationTest.contextLoads()` 全绿，`mvn -pl gateway-bootstrap spring-boot:run` 启动成功，`git commit` 完成。

### Task 12: 整体集成验证（本计划收尾）
- [ ] **目标**：全量编译 + 全量测试 + 依赖方向负向断言 + README 更新（构建命令与后续 changes roadmap）。
- [ ] **完成判据**：`mvn clean test` 全绿，依赖方向负向断言无输出，`README.md` 已更新，`git commit` 完成。

## 完成判据汇总

| 判据 | 命令/方式 |
|------|-----------|
| 全量编译 + 测试 | `mvn clean test` |
| domain 覆盖率 ≥90% | `mvn -pl gateway-domain test`（jacoco 自动检查） |
| domain 零框架 | `mvn -pl gateway-domain dependency:tree` 无 Spring/Jackson |
| bootstrap 启动 | `mvn -pl gateway-bootstrap spring-boot:run` 监听 8080 |
| 依赖方向 | 负向断言（见 Task 12 Step 2）无输出 |
| Spike 报告 | `docs/superpowers/spike/2026-08-12-saa-compat-report.md` 存在 |

全部判据通过后，本 change 可标记为完成，进入后续并行实现 changes（A2A/模型/会话/鉴权/可观测）。
