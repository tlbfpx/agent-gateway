# Proposal: 基础骨架搭建（add-foundation-skeleton）

> **状态：✅ 已完成**（2026-08-13）。验收标准全部达成，见末尾「实现结果」。

## 变更概述

本 change 建立 agent-gateway 项目的 Maven 多模块骨架、完成 0 阶段技术 Spike、落地 `gateway-domain` 领域核心（record + 端口契约），使项目可编译、domain 单元测试可通过。这是所有后续并行实现计划的地基。

## 动机

### 为什么需要这个 change

1. **统一依赖版本管理**：Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1 是里程碑版本，需要父 pom 统一管理版本，避免后续 11 个模块各自演进导致的版本冲突。

2. **强制执行架构约束**：洋葱/六边形架构要求 `gateway-domain` 零框架依赖、依赖方向严格单向（interfaces → application → domain ← infra）。通过骨架建立时强制定义，避免后续实现时边界模糊。

3. **为并行开发提供契约地基**：后续 A2A/模型/会话/鉴权/可观测等计划可完全并行开发，前提是 domain 契约（端口接口、record 类型、Identity 值对象）已定稿并被测试覆盖。

4. **验证技术栈可行性**：Spring Boot 4.0 与 SAA 2.0.0-M1 兼容性存在风险（spec §11），必须先 Spike 验证再写业务代码，避免实现期推翻技术选型。

### 不做这个 change 会怎样

- 各模块可能引入冲突版本的 Spring AI/SAA 依赖，运行期 `NoSuchMethodError`。
- domain 层可能被 Spring/Jackson 污染，导致后续 infra 替换成本高（如换 Consul、换 LLM Provider）。
- 并行开发时因缺少 domain 契约而互相等待或重复定义类型（UserId/TenantId/SessionId 等）。

## 范围

### 做什么

| 任务 | 交付物 |
|------|--------|
| **Maven 骨架** | 父 pom + 11 个子模块（domain/api/application/6 个 infra/interfaces/bootstrap），可编译 |
| **0 阶段 Spike** | 验证 Boot4+SAA2.0-M1 兼容性报告（`docs/superpowers/spike/2026-08-12-saa-compat-report.md`） |
| **domain 核心** | 全部 record（Session/Message/AgentCard/ModelDef/AuthPrincipal 等）+ 出站端口（ToolPort/AgentCardPort/ChatClientPort/LlmSession）+ Identity 值对象 |
| **domain 测试** | 单元测试覆盖率 ≥ 90%（JaCoCo 门禁） |
| **bootstrap 启动** | 可运行的 Spring Boot 应用（空壳，无业务端点） |
| **CI 基础** | `mvn clean test` 全绿 |

### 不做什么

- **任何 infra 业务逻辑**：A2A/Nacos/LLM/persistence/security/observability 的业务代码留待后续计划。
- **interfaces 控制器**：HTTP/SSE 端点留待「编排核心 + 流式 SSE 端点」计划。
- **example-agent 骨架**：spec §3.2 的 `example-agent/` 是后续「A2A + 示例 Agent」计划的交付物，本期不建（避免 Nacos 死代码）。

## 依赖与风险

### 技术依赖

| 依赖 | 版本 | 风险 |
|------|------|------|
| Spring Boot | 4.0.0 | 第三方 starter 可能未适配，由 Spike 验证 |
| Spring AI | 2.0.0-M1 | 里程碑版，API 可能变动 |
| Spring AI Alibaba | 2.0.0-M1 | 里程碑版，A2A/Admin 模块可能未完全适配 |

### 缓解措施

1. **Spike 前置门**：spec §11.1 的兼容性验证矩阵必须在进入编码前全部通过，不通过者立即确定 openai-compatible 兜底方案。
2. **domain 零依赖隔离**：SAA/Spring AI 依赖仅限 `gateway-infra-*`，domain 严格只依赖 JDK。
3. **适配层策略**：关键路径（LLM 装配、A2A 调用）写适配层，SAA API 变动时只改 infra，不影响 domain。

## 验收标准

1. `mvn clean test` 全绿（11 模块编译 + domain 测试 + bootstrap contextLoads）。
2. `mvn -pl gateway-domain test` 覆盖率 ≥ 90%（jacoco check）。
3. `mvn -pl gateway-bootstrap spring-boot:run` 启动成功，监听 8080。
4. `mvn -pl gateway-domain dependency:tree` 输出无 Spring/Jackson 依赖。
5. Spike 报告存在于 `docs/superpowers/spike/2026-08-12-saa-compat-report.md`。

## 实现结果（2026-08-13 完成）

| 验收项 | 结果 |
|---|---|
| `mvn clean verify` | ✅ BUILD SUCCESS（11 模块编译 + 56 domain 测试 + 1 bootstrap contextLoads + JaCoCo check 通过） |
| domain 覆盖率 | ✅ line 100% / branch 90% / instruction 99.4%（56 测试） |
| bootstrap 启动 | ✅ Tomcat 监听 8080，"Started GatewayApplication" |
| domain 零框架 | ✅ dependency:tree 编译期无 spring/jackson/reactor |
| Spike 报告 | ✅ 含 SAA 2.0.0-M1.1 打包缺陷发现 + 一行 exclude 缓解方案 |
| 依赖方向 | ✅ 负向断言通过：application/infra 不反向依赖；bootstrap→interfaces→application→domain 链路通 |

**额外产出**（Spike 发现，影响后续 change）：
- SAA 实际版本 `2.0.0-M1.1`（非 spec 原写的 2.0.0-M1，后者 Maven Central 404）。
- dashscope starter 缺 `DashScopeMultimodalEmbeddingAutoConfiguration` 类，需 `spring.autoconfigure.exclude` —— 记入 `add-multi-model` change。
- spec §3.3 已同步修订为零框架定稿（String schema / JDK Flow / LlmSession）。

12 个 Task 全部经 subagent-driven-development 双阶段评审（spec compliance + code quality）通过。

## 关联文档

- **项目提案**：`openspec/PROPOSAL.md`
- **设计文档（项目级）**：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§3 模块结构、§3.3 领域对象、§5.5 模型、§11 风险）
- **实现计划（详细 step）**：`docs/superpowers/plans/2026-08-12-foundation.md`
- **协同规范**：`AGENTS.md`（多 Agent 并行工作流）
- **本 change design**：`openspec/changes/add-foundation-skeleton/design.md`
- **本 change tasks**：`openspec/changes/add-foundation-skeleton/tasks.md`
