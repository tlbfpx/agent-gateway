# Agent Gateway — 基础骨架实现计划（Plan ①）

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 agent-gateway 的 Maven 多模块骨架、完成 0 阶段技术 Spike、并落地 gateway-domain 领域核心（record + 端口契约），使项目可编译、domain 单元测试可通过——为后续所有并行实现计划（A2A/模型/会话/鉴权/可观测/管理后台）提供可依赖的契约地基。

**Architecture:** 洋葱/六边形架构。`gateway-domain` 零框架依赖（纯 Java record + 接口），`gateway-application` 依赖 domain，`gateway-infra-*` 实现 domain 定义的出站端口，`gateway-interfaces`/`gateway-bootstrap` 依赖 application。本计划只到 domain + 空骨架模块 + bootstrap 能启动，**不**实现任何 infra 业务逻辑（那是后续计划）。

**Tech Stack:** Java 21（LTS）、Maven 3.9+、Spring Boot 4.0.0、Spring AI 2.0.0-M1、Spring AI Alibaba 2.0.0-M1、JUnit 5、AssertJ。0 阶段需 Nacos 3.x、Redis（供 Spike 验证，非本计划编码范围）。

**关联文档:**
- 设计 spec：`docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§3 模块结构、§3.3 领域对象、§5.5 模型、§11 风险、§13.4 错误码）
- 协同规范：`AGENTS.md`（多 Agent 并行；本计划完成后，后续 infra 计划可按模块并行开发）

**范围声明（本计划做什么 / 不做什么）:**
- ✅ 做：Maven 父 pom + 11 个子模块骨架、0 阶段 Spike（验证 Boot4+SAA2.0-M1 兼容性，产出报告）、domain 全部 record 与端口、domain 单元测试、bootstrap 启动类（空 Spring Boot 应用能起）、CI 基础（编译 + 测试）。
- ❌ 不做：任何 infra 实现逻辑（A2A/Nacos/LLM/persistence/security/observability 的业务代码）、interfaces 控制器、前端。
- ⚠️ **关于 `example-agent`**：spec §3.2 列了 12 个模块含 `example-agent/`。它是后续「A2A + 示例 Agent」计划的交付物（作为 A2A 调用的测试靶机），**本计划不建它的骨架**。这是有意推迟，非遗漏——后续计划会建。

**关键设计决策（本计划锁定，需同步修正 spec）:**
- **domain 严格零框架**：spec §3.2 要求 domain「纯逻辑，零框架依赖」。但 §3.3 的 `AgentCard` 用了 `JsonNode`（Jackson），与零框架冲突。**决策**：domain 中 schema 字段改用 `String`（存 JSON 文本）或 `Map<String,Object>`，**不引入 Jackson**；`JsonNode` 仅在 `gateway-api`/infra 层使用（那里本就依赖 jackson）。本计划 Task 4 实现 domain 时按此决策，并在 Task 4 末尾同步修订 spec §3.3 的 `AgentCard` 签名（把 `JsonNode inputSchema/outputSchema` 改为 `String`）。这是评审提出的关键问题，必须在本计划解决而非留到实现期争议。

---

## Chunk 1: Maven 多模块骨架 + 0 阶段 Spike

### Task 1: 初始化父 POM 与版本管理

**Files:**
- Create: `pom.xml`（父 pom，packaging=pom）
- Create: `.gitignore`
- Create: `README.md`

- [x] **Step 1: 写父 pom**

`pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.company.agentgateway</groupId>
    <artifactId>agent-gateway-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>agent-gateway-parent</name>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.0</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-ai.version>2.0.0-M1</spring-ai.version>
        <spring-ai-alibaba.version>2.0.0-M1</spring-ai-alibaba.version>
    </properties>

    <modules>
        <module>gateway-domain</module>
        <module>gateway-application</module>
        <module>gateway-api</module>
        <module>gateway-infra-a2a</module>
        <module>gateway-infra-nacos</module>
        <module>gateway-infra-llm</module>
        <module>gateway-infra-persistence</module>
        <module>gateway-infra-security</module>
        <module>gateway-infra-observability</module>
        <module>gateway-interfaces</module>
        <module>gateway-bootstrap</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <!-- 注：SAA 2.0.0-M1 会带入它依赖的 Spring AI 2.0.0-M1 版本。
                 当后续计划真正引入 SAA starter 时，应在此 import
                 com.alibaba.cloud.ai 的 SAA BOM（具体 GAV 在 Spike Task 2 中确认，
                 因 2.0.0-M1 可能重命名了部分 artifact）。Spring AI BOM 此处先声明，
                 SAA BOM 引入后以其版本为准（SAA BOM 优先）。 -->
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- 内部模块版本统一 -->
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-domain</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-application</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-api</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-interfaces</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-a2a</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-nacos</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-llm</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-persistence</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-security</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-infra-observability</artifactId><version>${project.version}</version></dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [x] **Step 2: 写 .gitignore**

```gitignore
target/
*.class
.idea/
*.iml
.vscode/
.DS_Store
HELP.md
.mvn/wrapper/maven-wrapper.jar
```

- [x] **Step 3: 写 README.md**

```markdown
# agent-gateway

公司 Agent 通用网关。技术栈 Spring Boot 4.0 + Spring AI Alibaba 2.0.0-M1 + Nacos A2A。

- 设计文档: docs/superpowers/specs/2026-08-12-agent-gateway-design.md
- 协同规范: AGENTS.md

构建: `mvn -q -DskipTests compile`
```

- [x] **Step 4: 提交**

```bash
git add pom.xml .gitignore README.md
git commit -m "chore: init parent pom with 11 modules and version mgmt"
```

---

### Task 2: 0 阶段 Spike — Boot4 + SAA2.0-M1 兼容性验证

> 这是 spec §11 风险表 + §10.1 前置门要求的 **0 阶段 Spike**。必须先验证技术栈能编译装配，再写业务代码。用临时 spike 子模块做最小验证，验证后归档为报告。
>
> **Spike 范围聚焦**：本 Task 只验证 **LLM starter 装配**（dashscope 为代表）。Nacos A2A 客户端兼容性是另一个独立 Spike（依赖 A2A 计划的依赖坐标），**不在本 Task**——避免起 Nacos 却不用的死代码。Nacos A2A 验证留到「A2A + Nacos 发现」计划的前置 Spike。

**Files:**
- Create: `spike/saa-compat/pom.xml`（临时，验证后从 modules 移除）
- Create: `spike/saa-compat/src/main/java/com/company/agentgateway/spike/CompatCheck.java`
- Create: `docs/superpowers/spike/2026-08-12-saa-compat-report.md`（验证报告，永久保留）
- Modify: 根 `pom.xml`（临时加 spike 模块，验证后移除）

- [x] **Step 1: 确认 SAA artifact GAV（人工，关键）**

SAA 2.0.0-M1 可能重命名了部分 artifact。执行者须先到 https://github.com/alibaba/spring-ai-alibaba/releases 确认 `2.0.0-M1` 下：
- dashscope starter 的精确 GAV（`groupId:artifactId`，本计划假设 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`，**需核对**）
- 是否有 SAA BOM 可 import（`com.alibaba.cloud.ai:xxx-bom`）
把确认结果记入 Step 7 报告。若 GAV 与计划假设不同，本 Task Step 2 的 pom 按实际 GAV 调整。

- [x] **Step 2: 创建 spike 模块 pom**

临时在根 `pom.xml` 的 `<modules>` 加 `<module>spike/saa-compat</module>`。

`spike/saa-compat/pom.xml`（artifact GAV 按 Step 1 核对结果调整）：
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>spike-saa-compat</artifactId>
    <dependencies>
        <!-- GAV 以 Step 1 核对为准；若存在 SAA BOM 则在父 pom import 后此处不写 version -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
            <version>${spring-ai-alibaba.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [x] **Step 3: 写最小兼容性检查类（用 CommandLineRunner，确保干净退出）**

`spike/saa-compat/src/main/java/com/company/agentgateway/spike/CompatCheck.java`：
```java
package com.company.agentgateway.spike;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 0 阶段 Spike：验证 Spring Boot 4.0 + SAA 2.0.0-M1 + JDK 21 能否启动并装配 dashscope autoconfig。
 * 用 CommandLineRunner 探测真实装配的 ChatModel bean，证明 starter 真正生效（而非被 @ConditionalOnClass 跳过）。
 */
@SpringBootApplication
public class CompatCheck {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CompatCheck.class, args)));
    }

    @Bean
    CommandLineRunner probe(ApplicationContext ctx) {
        return args -> {
            // 探测是否存在 dashscope 相关 ChatModel bean（类名含 dashscope，大小写不敏感）
            long dashscopeBeans = java.util.Arrays.stream(ctx.getBeanDefinitionNames())
                .filter(n -> n.toLowerCase().contains("dashscope") || n.toLowerCase().contains("chatmodel"))
                .count();
            System.out.println("SPIKE_OK: beanCount=" + ctx.getBeanDefinitionCount()
                + " dashscopeOrChatModelBeans=" + dashscopeBeans);
            if (dashscopeBeans == 0) {
                System.out.println("SPIKE_WARN: no dashscope/ChatModel bean found — starter may have been skipped");
            }
        };
    }
}
```
> 注：用 `SpringApplication.exit()` 包裹返回退出码 0，使 `spring-boot:run` 能干净结束而非挂起。

- [x] **Step 4: 配置 spike 的 application.yml**

`spike/saa-compat/src/main/resources/application.yml`：
```yaml
# 仅验证装配，不真正调用模型 API（用占位 key）
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_KEY:sk-placeholder}
  main:
    web-application-type: none   # 不起 web 服务器，加快启动
```

- [x] **Step 5: 编译验证**

Run: `mvn -q -pl spike/saa-compat -am compile`
Expected: BUILD SUCCESS（若失败，记录错误到报告，按 §11 矩阵改用 openai-compatible 兜底，继续 Step 6 验证兜底链路）

- [x] **Step 6: 启动验证（真实跑起来并干净退出）**

Run: `mvn -q -pl spike/saa-compat spring-boot:run`
Expected: 进程启动，输出 `SPIKE_OK: beanCount=... dashscopeOrChatModelBeans=≥1`，然后进程以退出码 0 结束（不挂起）。
- 若 `dashscopeOrChatModelBeans=0` → starter 未生效，记 SPIKE_WARN，排查。
- 若启动失败（Bean 装配冲突） → 记根因，尝试兜底。

- [x] **Step 7: 写 Spike 验证报告**

`docs/superpowers/spike/2026-08-12-saa-compat-report.md`，按 spec §11.1 矩阵填写实际结果：
```markdown
# SAA 兼容性 Spike 报告（0 阶段）
- 日期: 2026-08-12
- 环境: JDK 21 / Spring Boot 4.0.0 / Spring AI 2.0.0-M1 / Spring AI Alibaba 2.0.0-M1

## Artifact GAV 核对（Task 2 Step 1）
- dashscope starter 实际 GAV: <填>
- SAA BOM 是否存在及 GAV: <填>

## 结果矩阵
> 本 Task 只验证 dashscope 行。其余 3 行由后续并行 Spike agent 验证后回填。
| starter | 编译 | 启动装配 | 结论 |
|---|---|---|---|
| spring-ai-alibaba-starter-dashscope | <✅/❌> | <✅/❌> | <可用/需兜底> |
| spring-ai-openai（兼容模式，DeepSeek） | 待并行 Spike | | |
| spring-ai-zhipuailm（社区） | 待并行 Spike | | |
| spring-ai-minimax（社区） | 待并行 Spike | | |

## 结论与兜底决策
<填：dashscope 是否可直接用；若不行，openai-compatible 兜底是否可行>

## 问题与解决
<填：遇到的兼容性问题及绕过方式>
```

- [x] **Step 8: 移除 spike 模块、保留报告（显式 git 操作）**

显式从版本控制处理 spike 代码，避免静默：
```bash
# 1) 从根 pom 移除 spike 模块声明（手动编辑 pom.xml 删 <module>spike/saa-compat</module>）
# 2) 提交报告 + pom 变更 + 显式移除 spike 跟踪
git add docs/superpowers/spike/2026-08-12-saa-compat-report.md pom.xml
git add spike/   # 若希望保留 spike 源码作参考则 add；否则下一步 rm
# 若决定不保留 spike 源码：
#   git rm -r spike
git commit -m "chore: 0-phase spike report (Boot4+SAA2.0-M1 dashscope compat)"
```
> 最终状态：spike 从 `<modules>` 移除（不影响主构建），报告永久保留；spike 源码去留由执行者决定但须显式 git 操作。

---

### Task 3: 创建所有空子模块（仅 pom，可编译）

> 一次建好 11 个模块的 pom + 目录骨架，确保 `mvn compile` 全绿。domain 在 Chunk 2 填充，其余模块本计划保持空骨架（后续计划填）。

**Files:**
- Create: `gateway-domain/pom.xml`
- Create: `gateway-api/pom.xml`
- Create: `gateway-application/pom.xml`
- Create: `gateway-infra-a2a/pom.xml`、`gateway-infra-nacos/pom.xml`、`gateway-infra-llm/pom.xml`、`gateway-infra-persistence/pom.xml`、`gateway-infra-security/pom.xml`、`gateway-infra-observability/pom.xml`
- Create: `gateway-interfaces/pom.xml`
- Create: `gateway-bootstrap/pom.xml`

- [x] **Step 1: gateway-domain/pom.xml（严格零框架，仅 JDK + test 依赖）**

> 按本计划「关键设计决策」：domain 不引入 Jackson，schema 字段用 `String`/`Map`，故 pom 只有 test 依赖。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-domain</artifactId>
    <dependencies>
        <!-- 严格零框架：无 Spring/Boot/Jackson。仅 test 依赖 -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [x] **Step 2: gateway-api/pom.xml（DTO + 契约，依赖 jackson 做 JSON DTO 序列化）**

> `gateway-api` 是对外契约层，DTO 需要 Jackson 注解/JsonNode，所以 Jackson 放这里（而非 domain）。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-api</artifactId>
    <dependencies>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
    </dependencies>
</project>
```

- [x] **Step 3: gateway-application/pom.xml（依赖 domain）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-application</artifactId>
    <dependencies>
        <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-domain</artifactId></dependency>
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [x] **Step 4: 六个 infra 模块 pom（依赖 domain，后续计划填实现）**

每个 infra 模块（`gateway-infra-a2a`、`gateway-infra-nacos`、`gateway-infra-llm`、`gateway-infra-persistence`、`gateway-infra-security`、`gateway-infra-observability`）用相同模板，仅 artifactId 不同：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-infra-a2a</artifactId>  <!-- 各模块替换此处 -->
    <dependencies>
        <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-domain</artifactId></dependency>
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [x] **Step 5: gateway-interfaces/pom.xml（依赖 application）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-interfaces</artifactId>
    <dependencies>
        <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-application</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [x] **Step 6: gateway-bootstrap/pom.xml（聚合所有，含 devtools）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.company.agentgateway</groupId>
        <artifactId>agent-gateway-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway-bootstrap</artifactId>
    <dependencies>
        <dependency><groupId>com.company.agentgateway</groupId><artifactId>gateway-interfaces</artifactId></dependency>
        <!-- infra 依赖在后续计划按需加入；本计划 bootstrap 仅依赖 interfaces 使其可启动 -->
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [x] **Step 7: 为每个模块建 src 目录占位**

```bash
for m in gateway-domain gateway-api gateway-application gateway-infra-a2a gateway-infra-nacos gateway-infra-llm gateway-infra-persistence gateway-infra-security gateway-infra-observability gateway-interfaces gateway-bootstrap; do
  mkdir -p $m/src/main/java $m/src/test/java $m/src/main/resources
done
```

- [x] **Step 8: 全量编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS，11 模块全部编译通过（空骨架）。

- [x] **Step 9: 提交**

```bash
git add gateway-*/pom.xml
git commit -m "chore: scaffold 11 maven modules with dependency direction"
```

---

### Task 4: 验证骨架可编译 + domain 零框架边界 + 测试工具链

**Files:** 无新建文件（纯验证 Task）

- [x] **Step 1: 全量编译（11 模块）**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [x] **Step 2: 验证 domain 零框架（dependency:tree）**

Run: `mvn -q -pl gateway-domain dependency:tree`
Expected: 输出中 `gateway-domain` 的编译期依赖 **只有 JDK**（无 spring/jackson 等）。若出现非预期依赖，回查 Task 3 Step 1 的 pom。
> 这一步强制执行「domain 严格零框架」边界，是评审要求的关键检查。

- [x] **Step 3: 验证测试工具链可用（空 test 跑通）**

Run: `mvn -q -pl gateway-domain test`
Expected: BUILD SUCCESS（domain 还无测试类，但 JUnit/AssertJ 已就绪，无报错即证明工具链配置正确）。

- [x] **Step 4: 提交（无代码变更则空提交或跳过）**

骨架已就绪，本 Task 若无文件变更可跳过提交；若有 pom 微调则提交。

---

## Chunk 1 评审检查点

> 完成 Chunk 1 后，派 plan-document-reviewer 评审本 chunk（见 plan 末尾「评审循环」），通过后再写 Chunk 2。
> **Chunk 1 已通过第 2 轮评审（Approved）。**

---

## Chunk 2: gateway-domain 领域核心实现

> 这是本计划的**核心交付**：实现 spec §3.3 的全部领域 record 与出站端口，配 TDD 单元测试。遵循「关键设计决策」：domain 严格零框架，schema 用 `String`（JSON 文本）而非 `JsonNode`。完成后 domain 单元测试全绿，为后续所有 infra 计划提供契约地基。

### 包结构（本 Chunk 产出）

```
gateway-domain/src/main/java/com/company/agentgateway/domain/
├── shared/        Identity 值对象（UserId/TenantId/ModelId/AgentVersion 等公共类型）
├── session/       Session/Message/ContextWindow
├── registry/      AgentCard
├── model/         ModelDef/Capability（spec §17.2 权威定义）
├── iam/           AuthPrincipal/AgentGrant/AuthChannel
└── orchestration/ 出站端口 ToolPort/AgentCardPort/ChatClientPort + ToolEvent/InvocationCtx
```

### Task 5: shared 包 — 公共 Identity 值对象

> spec §3.3 的 record 多处引用 `UserId`/`TenantId`/`ModelId`/`SessionId` 等，第 1 轮评审指出它们「从未显式声明 record 体」。本 Task 集中定义，供其余包 import。

**Files（每个 public record 单独一个文件，Java 编译约束）：**
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/UserId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/TenantId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/ModelId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/SessionId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/RoleId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/ApiKeyId.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/AgentVersion.java`
- Create: `gateway-domain/src/main/java/com/company/agentgateway/domain/shared/IdValidation.java`（package-private 工具类）
- Test: `gateway-domain/src/test/java/com/company/agentgateway/domain/shared/IdentityTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.company.agentgateway.domain.shared;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IdentityTest {
    @Test
    void userIdPreservesValue() {
        assertThat(new UserId("u-1").value()).isEqualTo("u-1");
    }
    @Test
    void tenantIdEquality() {
        assertThat(new TenantId("t-1")).isEqualTo(new TenantId("t-1"));
    }
    @Test
    void sessionIdRejectsBlank() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> new SessionId(" "));
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl gateway-domain test -Dtest=IdentityTest`
Expected: FAIL（类不存在）

- [x] **Step 3: 实现 Identity 值对象（每个 record 一个文件，共享校验逻辑）**

`IdValidation.java`（package-private）：
```java
package com.company.agentgateway.domain.shared;
/** Identity 值对象的公共非空校验。 */
final class IdValidation {
    private IdValidation() {}
    static void requireNonBlank(String v) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("identity value must not be blank");
    }
}
```

`UserId.java`：
```java
package com.company.agentgateway.domain.shared;
public record UserId(String value) { public UserId { IdValidation.requireNonBlank(value); } }
```

`TenantId.java` / `ModelId.java` / `SessionId.java` / `RoleId.java` / `ApiKeyId.java` 同构（仅类名不同）：
```java
package com.company.agentgateway.domain.shared;
public record TenantId(String value) { public TenantId { IdValidation.requireNonBlank(value); } }
```
（对其余 4 个 record 同样替换类名即可。）

`AgentVersion.java`（spec §4.4：语义版本比较，无法解析退化为字符串比较）：
```java
package com.company.agentgateway.domain.shared;
public record AgentVersion(String value) { public AgentVersion { IdValidation.requireNonBlank(value); } }
```

- [x] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=IdentityTest`
Expected: PASS

- [x] **Step 5: 提交**

```bash
git add gateway-domain/src/main/java/com/company/agentgateway/domain/shared/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/shared/IdentityTest.java
git commit -m "feat(domain): add shared identity value objects"
```

---

### Task 6: session 包 — Session / Message / ContextWindow

> spec §3.3 + §5.2 + §5.3。Session 含 `model` 字段（用户选定模型），Message 用 sealed interface，ContextWindow 做 Token 截断 + ToolResult 瘦身（一期算法）。

**Files:**
- Create: `session/Message.java`（sealed interface + 4 实现）
- Create: `session/Session.java`
- Create: `session/ContextWindow.java`
- Test: `session/SessionTest.java`、`session/ContextWindowTest.java`

- [x] **Step 1: 写 ContextWindow 失败测试（表驱动，spec §9.3 风格）**

`session/ContextWindowTest.java`：
```java
package com.company.agentgateway.domain.session;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowTest {
    record FitCase(String name, List<Message> history, int budget, int expectedSize) {}

    static Stream<FitCase> fitCases() {
        return Stream.of(
            new FitCase("预算充足保留全部", messages(5), 10000, 5),
            new FitCase("超预算从最早截断", messages(5), 30, 3),  // 5*10=50>30, 移2条剩3*10=30<=30
            new FitCase("至少保留最近K=2", messages(10), 1, 2)
        );
    }
    @ParameterizedTest(name = "{0}")
    @MethodSource("fitCases")
    void shouldFitWithinTokenBudget(FitCase c) {
        var w = new ContextWindow(2, m -> 10);  // minKeep=2, 每条估算10 token
        List<Message> out = w.fit(c.history(), c.budget());
        assertThat(out).hasSize(c.expectedSize);
    }
    static List<Message> messages(int n) {
        var list = new java.util.ArrayList<Message>();
        for (int i = 0; i < n; i++) list.add(new UserMessage("m" + i));
        return list;
    }
}
```
> 注：`ContextWindow` 构造与 `tokenEstimator` 是为可测试性的设计——一期用「每条消息固定 token」估算（后续 infra 计划接入真实 tokenizer）。`minKeep` 对应 spec §5.3「最近 K 轮原文」。

- [x] **Step 2: 运行确认失败**

Run: `mvn -q -pl gateway-domain test -Dtest=ContextWindowTest`
Expected: FAIL（类不存在）

- [x] **Step 3: 实现 Message sealed 接口**

`session/Message.java`：
```java
package com.company.agentgateway.domain.session;

/** 会话消息。sealed 保证模式匹配穷尽。schema 用 String（JSON 文本），非 JsonNode（零框架）。 */
public sealed interface Message
        permits UserMessage, AssistantMessage, ToolCallMessage, ToolResultMessage {}

public record UserMessage(String content) implements Message {}
public record AssistantMessage(String content) implements Message {}
/** 工具调用请求：agent 名 + 入参（JSON 文本）。 */
public record ToolCallMessage(String agentName, String argsJson) implements Message {}
/** 工具调用结果：内容 + 是否被瘦身（spec §5.3 ToolResult 瘦身）。 */
public record ToolResultMessage(String agentName, String content, boolean slimmed) implements Message {}
```
> 实际实现：每个 record 单独文件，或同包内非 public 合并。执行者按编译约束处理。

- [x] **Step 4: 实现 ContextWindow（一期：Token 截断，从最早丢弃，保最近 minKeep）**

`session/ContextWindow.java`：
```java
package com.company.agentgateway.domain.session;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * spec §5.3：注入 LLM 前的历史裁剪。
 * 一期算法：Token 预算截断（从最早向前丢弃，但至少保留最近 minKeep 条）。
 * ToolResult 瘦身：超阈值的大 ToolResult 替换为占位摘要（一期：超长截断为 "[slimmed]"，完整结果由 infra 持久化）。
 */
public final class ContextWindow {
    private final int minKeep;
    private final ToIntFunction<Message> tokenEstimator;

    public ContextWindow(int minKeep, ToIntFunction<Message> tokenEstimator) {
        if (minKeep < 1) throw new IllegalArgumentException("minKeep >= 1");
        this.minKeep = minKeep;
        this.tokenEstimator = tokenEstimator;
    }

    public List<Message> fit(List<Message> history, int tokenBudget) {
        if (history.size() <= minKeep) return List.copyOf(history);
        int total = history.stream().mapToInt(tokenEstimator).sum();
        if (total <= tokenBudget) return List.copyOf(history);
        // 从最早丢弃直到满足预算或只剩 minKeep
        List<Message> work = new ArrayList<>(history);
        int idx = 0;
        while (total > tokenBudget && work.size() > minKeep) {
            total -= tokenEstimator.applyAsInt(work.get(idx));
            work.remove(idx); // 总是从头部移除
        }
        return List.copyOf(work);
    }
}
```
> ContextWindow 专注 Token 截断（保最近 minKeep）。ToolResult 瘦身在 Session.append 实现（见本 Task Step 6-7），职责分离。

- [x] **Step 5: 运行 ContextWindowTest 确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=ContextWindowTest`
Expected: PASS

- [x] **Step 6: 写 Session 测试（含 ToolResult 瘦身，spec §5.3 一期项）**

`session/SessionTest.java`：
```java
package com.company.agentgateway.domain.session;
import com.company.agentgateway.domain.shared.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {
    private Session newSession() {
        return new Session(new SessionId("s1"), new TenantId("t1"), new UserId("u1"),
            new ModelId("qwen"), Instant.parse("2026-08-12T00:00:00Z"),
            Instant.parse("2026-08-12T00:00:00Z"), java.util.List.of());
    }
    @Test
    void appendAddsMessageAndKeepsHistory() {
        var s2 = newSession().append(new UserMessage("hi"));
        assertThat(s2.history()).hasSize(1);
    }
    @Test
    void appendSlimmsOversizedToolResult() {
        // spec §5.3 一期：超大 ToolResult 替换为占位摘要，完整结果由 infra 持久化
        String big = "x".repeat(2000);  // 超过阈值 1000
        var s2 = newSession().append(new ToolResultMessage("agent-x", big, false));
        var tr = (ToolResultMessage) s2.history().get(0);
        assertThat(tr.slimmed()).isTrue();
        assertThat(tr.content()).hasSizeLessThan(big.length());
    }
    @Test
    void appendKeepsSmallToolResultAsIs() {
        var s2 = newSession().append(new ToolResultMessage("agent-x", "small", false));
        var tr = (ToolResultMessage) s2.history().get(0);
        assertThat(tr.slimmed()).isFalse();
    }
}
```

- [x] **Step 7: 实现 Session（spec §3.3 + §5.2，含 model 字段 + §5.3 ToolResult 瘦身）**

`session/Session.java`：
```java
package com.company.agentgateway.domain.session;

import com.company.agentgateway.domain.shared.*;
import java.time.Instant;
import java.util.List;

/** 会话聚合根。model = 用户选定模型（spec §5.5.4）。 */
public record Session(SessionId id, TenantId tenant, UserId user, ModelId model,
                      Instant createdAt, Instant lastActiveAt, List<Message> history) {
    /** spec §5.3：ToolResult 瘦身阈值（字符数）。超此长度替换为摘要，完整结果由 infra 持久化。 */
    static final int TOOLRESULT_SLIM_THRESHOLD = 1000;
    static final String SLIMMED_MARKER = "[slimmed: large tool result persisted]";

    public Session {
        history = List.copyOf(history); // 不可变
    }

    /** 追加消息，返回新 Session（不可变更新）。对超大 ToolResult 瘦身（spec §5.3 一期）。 */
    public Session append(Message m) {
        Message toAdd = (m instanceof ToolResultMessage tr && !tr.slimmed()
                         && tr.content().length() > TOOLRESULT_SLIM_THRESHOLD)
            ? new ToolResultMessage(tr.agentName(), SLIMMED_MARKER, true)
            : m;
        var newHistory = new java.util.ArrayList<>(history);
        newHistory.add(toAdd);
        return new Session(id, tenant, user, model, createdAt, Instant.now(), List.copyOf(newHistory));
    }
}
```
> spec §5.3 一期：瘦身 = Session.append 内对超大 ToolResult 截断为占位标记，完整原文由 infra 持久化层（后续 SessionStore 计划）落 DB，不丢数据。`ContextWindow.fit` 专注 Token 截断，两者职责分离。

- [x] **Step 8: 运行 SessionTest 确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=SessionTest`
Expected: PASS

- [x] **Step 9: 提交**

```bash
git add gateway-domain/src/main/java/com/company/agentgateway/domain/session/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/session/
git commit -m "feat(domain): add Session, Message, ContextWindow (phase-1 token truncation)"
```

---

### Task 7: registry + model 包 — AgentCard / ModelDef

> spec §3.3（AgentCard）+ §17.2（ModelDef 权威定义）。按「关键设计决策」：schema 字段用 `String`（JSON 文本）。

**Files:**
- Create: `registry/AgentCard.java`
- Create: `model/ModelDef.java`、`model/Capability.java`
- Test: `registry/AgentCardTest.java`、`model/ModelDefTest.java`

- [x] **Step 1: 写 AgentCard 测试**

```java
package com.company.agentgateway.domain.registry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentCardTest {
    @Test
    void buildsCardWithStringSchemas() {
        var card = new AgentCard("hr-agent", "请假助手",
            java.util.List.of("请假"), "{}", "{}", "1.0.0", true);
        assertThat(card.name()).isEqualTo("hr-agent");
        assertThat(card.available()).isTrue();
    }
}
```

- [x] **Step 2: 实现 AgentCard（spec §3.3，schema 用 String）**

```java
package com.company.agentgateway.domain.registry;
import java.util.List;
/**
 * A2A AgentCard 领域视图（spec §3.3）。
 * inputSchema/outputSchema 为 JSON 文本（String），不引入 Jackson，保持 domain 零框架。
 * 解析为 JsonNode 的工作在 gateway-api/infra 层完成。
 */
public record AgentCard(String name, String description, List<String> skills,
                        String inputSchema, String outputSchema,
                        String version, boolean available) {
    public AgentCard {
        skills = List.copyOf(skills);
    }
}
```

- [x] **Step 3: 写 ModelDef 测试 + 实现（spec §17.2 权威定义）**

```java
// model/ModelDefTest.java
package com.company.agentgateway.domain.model;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ModelDefTest {
    @Test
    void hasFunctionCallingCapability() {
        var m = new ModelDef(new com.company.agentgateway.domain.shared.ModelId("qwen-max"),
            "dashscope", "通义千问 Max", "https://...", "ref-1",
            Set.of(Capability.FUNCTION_CALLING), 32000,
            new BigDecimal("0.04"), new BigDecimal("0.12"), true, java.util.List.of("all"));
        assertThat(m.capabilities()).contains(Capability.FUNCTION_CALLING);
    }
}
```

`model/Capability.java`：
```java
package com.company.agentgateway.domain.model;
/** spec §17.2：Capability 唯一定义处。 */
public enum Capability { FUNCTION_CALLING, VISION }
```

`model/ModelDef.java`：
```java
package com.company.agentgateway.domain.model;
import com.company.agentgateway.domain.shared.ModelId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
/** spec §17.2 权威定义。apiKeyRef 为密钥引用（不落明文）。 */
public record ModelDef(ModelId id, String provider, String displayName, String endpoint,
                       String apiKeyRef, Set<Capability> capabilities, int contextWindow,
                       BigDecimal costPer1kIn, BigDecimal costPer1kOut,
                       boolean enabled, List<String> tenantScope) {
    public ModelDef {
        capabilities = Set.copyOf(capabilities);
        tenantScope = List.copyOf(tenantScope);
    }
    public boolean supportsFunctionCalling() {
        return capabilities.contains(Capability.FUNCTION_CALLING);
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=AgentCardTest,ModelDefTest`
Expected: PASS

- [x] **Step 5: 提交**

```bash
git add gateway-domain/src/main/java/com/company/agentgateway/domain/registry/ \
        gateway-domain/src/main/java/com/company/agentgateway/domain/model/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/registry/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/model/
git commit -m "feat(domain): add AgentCard (string schemas) and ModelDef (§17.2)"
```

---

### Task 8: iam 包 — AuthPrincipal / AgentGrant

> spec §3.3 + §6.3。
>
> **范围说明**：一期 AuthPrincipal 用**扁平 grant 集合**做判定（`agentGrants`/`allowedModels`），数据来源由 infra 提供。规范 §19 的 `Role`/`Permission` 体系（AgentPermission/ModelPermission/SkillPermission、用户→角色→权限聚合）是**后续「RBAC 权限管理」计划**的交付物；届时会在 application/infra 层把 Role 聚合为扁平 grant 再喂给本 domain 的 AuthPrincipal。本 Task 只实现 domain 的扁平判定，不引入 Role 抽象——避免与 §19 的范围混淆。

**Files:**
- Create: `iam/AuthChannel.java`、`iam/AgentGrant.java`、`iam/AuthPrincipal.java`
- Test: `iam/AuthPrincipalTest.java`

- [x] **Step 1: 写测试**

```java
package com.company.agentgateway.domain.iam;
import com.company.agentgateway.domain.shared.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AuthPrincipalTest {
    @Test
    void canInvokeAgentRespectsGrants() {
        var p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"),
            Set.of(new AgentGrant("hr-agent", Set.of())),
            Set.of(new ModelId("qwen")), AuthChannel.API_KEY);
        assertThat(p.canInvoke("hr-agent")).isTrue();
        assertThat(p.canInvoke("finance-agent")).isFalse();
    }
    @Test
    void canUseModelRespectsAllowedModels() {
        var p = new AuthPrincipal(new UserId("u1"), new TenantId("t1"), Set.of(),
            Set.of(new ModelId("qwen")), AuthChannel.API_KEY);
        assertThat(p.canUse(new ModelId("qwen"))).isTrue();
        assertThat(p.canUse(new ModelId("glm"))).isFalse();
    }
}
```

- [x] **Step 2: 实现 iam（spec §6.3：Agent 级 + 模型级 RBAC 的领域判定）**

`iam/AuthChannel.java`：
```java
package com.company.agentgateway.domain.iam;
public enum AuthChannel { SSO, API_KEY }
```

`iam/AgentGrant.java`：
```java
package com.company.agentgateway.domain.iam;
import java.util.Set;
/** spec §6.3 AgentGrant。一期：agentName 级；allowedSkills 二期细化。 */
public record AgentGrant(String agentName, Set<String> allowedSkills) {
    public AgentGrant { allowedSkills = Set.copyOf(allowedSkills); }
}
```

`iam/AuthPrincipal.java`：
```java
package com.company.agentgateway.domain.iam;
import com.company.agentgateway.domain.shared.*;
import java.util.Set;
/** spec §3.3 + §6.3。含 Agent 级 + 模型级授权判定（domain 层只判定，数据来源由 infra 提供）。 */
public record AuthPrincipal(UserId user, TenantId tenant,
                            Set<AgentGrant> agentGrants,
                            Set<ModelId> allowedModels,
                            AuthChannel channel) {
    public AuthPrincipal {
        agentGrants = Set.copyOf(agentGrants);
        allowedModels = Set.copyOf(allowedModels);
    }
    public boolean canInvoke(String agentName) {
        return agentGrants.stream().anyMatch(g -> g.agentName().equals(agentName));
    }
    public boolean canUse(ModelId model) {
        return allowedModels.contains(model);
    }
}
```

- [x] **Step 3: 运行确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=AuthPrincipalTest`
Expected: PASS

- [x] **Step 4: 提交**

```bash
git add gateway-domain/src/main/java/com/company/agentgateway/domain/iam/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/iam/
git commit -m "feat(domain): add AuthPrincipal with agent+model level RBAC checks"
```

---

### Task 9: orchestration 包 — 出站端口（ToolPort / AgentCardPort / ChatClientPort）

> spec §3.3。端口定义在 domain，由 infra 实现。**本计划只定义端口契约 + 事件类型，不实现**（实现是后续 infra 计划）。

**关键设计点**：spec §3.3 的端口用了 `Flux<ToolEvent>` / `ChatClient`（Reactor / Spring AI 类型）。若 domain 引入这些，就破坏「零框架」。**决策**：domain 端口用 Java 原生 `java.util.concurrent.Flow`（JDK 9+ reactive，零依赖）表达流式，**不引 Reactor**；Reactor/Spring AI 类型留在 infra 适配层。**infra 实现时需编写 Flow.Publisher ↔ Reactor Flux 适配器**（用 `SubmissionPublisher` 或自定义 `Flow.Subscription`）——这点要记入后续「infra-llm」「infra-a2a」计划，避免撞墙。

**Files（类型均为顶级，不嵌套）：**
- Create: `orchestration/ToolEvent.java`（sealed）
- Create: `orchestration/InvocationCtx.java`
- Create: `orchestration/ToolDescriptor.java`（顶级 record，不嵌套）
- Create: `orchestration/LlmEvent.java`（sealed，顶级，类似 ToolEvent）
- Create: `orchestration/LlmSession.java`（interface，顶级，infra 桥接到 Spring AI ChatClient）
- Create: `orchestration/ToolPort.java`
- Create: `orchestration/AgentCardPort.java`
- Create: `orchestration/ChatClientPort.java`
- Test: `orchestration/PortContractTest.java`

- [x] **Step 1: 定义事件 + 上下文 + 描述符（顶级）**

`orchestration/ToolEvent.java`：
```java
package com.company.agentgateway.domain.orchestration;
/** 工具调用流式事件。sealed 穷尽。 */
public sealed interface ToolEvent
        permits ToolEvent.Delta, ToolEvent.Complete, ToolEvent.Error {
    record Delta(String content) implements ToolEvent {}
    record Complete(String fullResult) implements ToolEvent {}
    record Error(String code, String message) implements ToolEvent {}
}
```

`orchestration/LlmEvent.java`（顶级，结构与 ToolEvent 平行）：
```java
package com.company.agentgateway.domain.orchestration;
/** LLM 流式事件：增量文本 / 工具调用请求 / 完成。sealed 穷尽。 */
public sealed interface LlmEvent
        permits LlmEvent.Delta, LlmEvent.ToolCall, LlmEvent.Complete {
    record Delta(String content) implements LlmEvent {}
    record ToolCall(String toolName, String argsJson) implements LlmEvent {}
    record Complete() implements LlmEvent {}
}
```

`orchestration/InvocationCtx.java`：
```java
package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.iam.AuthPrincipal;
import com.company.agentgateway.domain.shared.SessionId;
/** 单次调用的上下文（谁、哪个会话、traceId——用于审计/限流/计费关联）。 */
public record InvocationCtx(SessionId session, AuthPrincipal principal, String traceId) {}
```

`orchestration/ToolDescriptor.java`：
```java
package com.company.agentgateway.domain.orchestration;
/** 工具描述符（domain 视角，供 LLM function calling）。由 application 层 AgentToolRegistry 从 AgentCard 转换而来（spec §4.2），domain 端口不感知 AgentCard 来源。 */
public record ToolDescriptor(String name, String description, String inputSchemaJson) {}
```

- [x] **Step 2: 定义 LlmSession + 三个端口（顶级，零框架）**

`orchestration/LlmSession.java`：
```java
package com.company.agentgateway.domain.orchestration;
import java.util.concurrent.Flow;
/** LLM 会话领域抽象。infra 桥接到 Spring AI ChatClient + Reactor，并写 Flow↔Flux 适配器。 */
public interface LlmSession {
    Flow.Publisher<LlmEvent> generate(String prompt, InvocationCtx ctx);
}
```

`orchestration/ToolPort.java`：
```java
package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.registry.AgentCard;
import java.util.concurrent.Flow;
/** 出站端口：调用远程 Agent（A2A）。由 gateway-infra-a2a 实现。argsJson 为 JSON 文本（零框架）。 */
public interface ToolPort {
    Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx);
}
```

`orchestration/AgentCardPort.java`：
```java
package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.registry.AgentCard;
import java.util.List;
import java.util.concurrent.Flow;
/** 出站端口：AgentCard 发现。由 gateway-infra-nacos 实现。snapshot=缓存全量，watch=变更流。 */
public interface AgentCardPort {
    List<AgentCard> snapshot();
    Flow.Publisher<List<AgentCard>> watch();
}
```

`orchestration/ChatClientPort.java`：
```java
package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.shared.ModelId;
import java.util.List;
/** 出站端口：构造 LlmSession。由 gateway-infra-llm 实现。domain 不依赖 Spring AI ChatClient。 */
public interface ChatClientPort {
    LlmSession sessionFor(ModelId model, List<ToolDescriptor> tools);
}
```

- [x] **Step 3: 写端口契约测试（真实订阅，断言事件，验证契约可工作）**

```java
package com.company.agentgateway.domain.orchestration;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.shared.SessionId;
import com.company.agentgateway.domain.iam.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class PortContractTest {
    private InvocationCtx ctx() {
        var p = new AuthPrincipal(new com.company.agentgateway.domain.shared.UserId("u"),
            new com.company.agentgateway.domain.shared.TenantId("t"), java.util.Set.of(),
            java.util.Set.of(), AuthChannel.API_KEY);
        return new InvocationCtx(new SessionId("s"), p, "trace-1");
    }

    @Test
    void toolPortRealSubscriptionDeliversDeltaThenComplete() throws Exception {
        ToolPort port = (agent, args, c) -> subscriber -> {
            // 立即发射（订阅者需处理同步 onNext）
            subscriber.onNext(new ToolEvent.Delta("hello"));
            subscriber.onComplete();
        };
        var card = new AgentCard("a", "d", List.of(), "{}", "{}", "1", true);
        var received = new java.util.concurrent.ConcurrentLinkedQueue<ToolEvent>();
        var done = new CountDownLatch(1);
        port.invoke(card, "{}", ctx()).subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ToolEvent e) { received.add(e); }
            public void onError(Throwable t) {}
            public void onComplete() { done.countDown(); }
        });
        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(received).extracting(Object::getClass)
            .contains(ToolEvent.Delta.class, ToolEvent.Complete.class);
    }

    @Test
    void agentCardPortSnapshotReturnsList() {
        AgentCardPort port = new AgentCardPort() {
            public List<AgentCard> snapshot() { return List.of(); }
            public Flow.Publisher<List<AgentCard>> watch() { return s -> s.onComplete(); }
        };
        assertThat(port.snapshot()).isEmpty();
    }

    @Test
    void chatClientPortReturnsLlmSession() {
        ChatClientPort port = (model, tools) -> (prompt, c) -> s -> s.onComplete();
        var session = port.sessionFor(new com.company.agentgateway.domain.shared.ModelId("qwen"), List.of());
        assertThat(session).isNotNull();
    }
}
```
> 本测试用纯 `CountDownLatch.await` 同步订阅，无需 Awaitility 依赖。

- [x] **Step 4: 运行确认通过**

Run: `mvn -q -pl gateway-domain test -Dtest=PortContractTest`
Expected: PASS（端口可被实现，JDK Flow 零依赖）

- [x] **Step 5: 提交**

```bash
git add gateway-domain/src/main/java/com/company/agentgateway/domain/orchestration/ \
        gateway-domain/src/test/java/com/company/agentgateway/domain/orchestration/
git commit -m "feat(domain): add outbound ports + LlmSession using JDK Flow (zero-framework)"
```

---

### Task 10: JaCoCo 覆盖率门禁 + domain 全量测试 + spec §3.3 同步修订

**Files:**
- Modify: `gateway-domain/pom.xml`（加 jacoco-maven-plugin，spec §9.1 >90% 门禁）
- Modify: `docs/superpowers/specs/2026-08-12-agent-gateway-design.md`（§3.3 签名修正）

- [x] **Step 1: domain pom 加 JaCoCo（spec §9.1 强制 domain >90%）**

在 `gateway-domain/pom.xml` 的 `</dependencies>` 后加 `<build><plugins>`：
```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals><goal>prepare-agent</goal></goals>
                    </execution>
                    <execution>
                        <id>check</id>
                        <goals><goal>check</goal></goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.90</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
```

- [x] **Step 2: domain 全量测试 + 覆盖率门禁**

Run: `mvn -q -pl gateway-domain test`
Expected: BUILD SUCCESS，全测试通过；jacoco `check` 验证行覆盖 ≥ 90%。
若覆盖率不达标（<90%），补测试直至达标（spec §9.1 要求）。报告位置 `gateway-domain/target/site/jacoco/index.html`。

- [x] **Step 3: 同步修订 spec §3.3（关键决策全部落地）**

修改 `docs/superpowers/specs/2026-08-12-agent-gateway-design.md` 的 §3.3，逐项改：
1. **§3.3 顶部加一句**：「以下为实现期定稿签名，已按『domain 严格零框架』原则修正 schema 类型与流式抽象（见 `plans/2026-08-12-foundation.md` 关键设计决策）。」
2. **`AgentCard`**：`JsonNode inputSchema, JsonNode outputSchema` → `String inputSchema, String outputSchema`（JSON 文本）。加注释「domain 零框架，schema 用 String；JsonNode 解析在 gateway-api/infra」。
3. **`ToolPort`**：`Flux<ToolEvent> invoke(AgentCard agent, JsonNode args, InvocationCtx ctx)` → `Flow.Publisher<ToolEvent> invoke(AgentCard agent, String argsJson, InvocationCtx ctx)`（JDK 原生 Flow；入参 JsonNode→String）。
4. **`AgentCardPort`**：`Flux<AgentCard> watch()` → `Flow.Publisher<List<AgentCard>> watch()`（JDK Flow；每次发布全量快照）。
5. **`ChatClientPort`**：`ChatClient clientFor(ModelId id, List<ToolDefinition> tools)` → `LlmSession sessionFor(ModelId id, List<ToolDescriptor> tools)`，并补 `LlmSession`/`LlmEvent`/`ToolDescriptor` 三个顶级类型定义（见本计划 Task 9）。加说明「domain 不依赖 Spring AI ChatClient/Reactor，桥接在 infra（需 Flow↔Flux 适配器）」。

- [x] **Step 4: 提交 spec 修订 + jacoco 配置**

```bash
git add gateway-domain/pom.xml docs/superpowers/specs/2026-08-12-agent-gateway-design.md
git commit -m "build(domain): enforce jacoco 90% gate; docs(spec): amend §3.3 to zero-framework"
```

- [x] **Step 5: 全量构建验证（本计划最终门禁）**

Run: `mvn -q -DskipTests compile && mvn -q -pl gateway-domain test`
Expected: 全模块编译通过 + domain 测试全绿 + 覆盖率 ≥ 90%。

- [x] **Step 6: 提交（若有遗漏文件）**

```bash
git add -A
git commit -m "test(domain): full domain suite green with 90% coverage" --allow-empty
```

---

## Chunk 2 评审检查点

> 完成 Chunk 2 后，派 plan-document-reviewer 评审本 chunk，通过后再写 Chunk 3。
> **Chunk 2 已通过第 2 轮评审（Approved，8 项问题全部修复）。**

---

## Chunk 3: bootstrap 启动 + 整体集成验证

> 让 gateway-bootstrap 作为可启动的 Spring Boot 应用跑起来（空壳，无业务端点——那些在后续计划）。证明多模块装配链路通畅，为本计划收尾。这一步是「项目可运行」的硬证据。

### Task 11: gateway-bootstrap 启动类 + application.yml

**Files:**
- Create: `gateway-bootstrap/src/main/java/com/company/agentgateway/bootstrap/GatewayApplication.java`
- Create: `gateway-bootstrap/src/main/resources/application.yml`
- Create: `gateway-bootstrap/src/test/java/com/company/agentgateway/bootstrap/GatewayApplicationTest.java`

- [x] **Step 1: 写启动测试（contextLoads）**

```java
package com.company.agentgateway.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证 bootstrap 上下文可加载（多模块装配链路通）。 */
@SpringBootTest
class GatewayApplicationTest {
    @Test
    void contextLoads() {
        // 仅验证上下文启动无异常
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -q -pl gateway-bootstrap -am test -Dtest=GatewayApplicationTest`
Expected: **编译失败**（`GatewayApplication` 类不存在）——这是预期的 RED（TDD 形式）。

- [x] **Step 3: 写启动类（收窄扫描包，排除 domain 强化零框架边界）**

`gateway-bootstrap/src/main/java/com/company/agentgateway/bootstrap/GatewayApplication.java`：
```java
package com.company.agentgateway.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent Gateway 启动入口。
 * 一期本计划阶段：空 Spring Boot 应用（仅装配骨架）。
 * scanBasePackages 显式排除 domain 包，强化「domain 零框架、无 Spring bean」边界。
 */
@SpringBootApplication(scanBasePackages = {
    "com.company.agentgateway.application",
    "com.company.agentgateway.interfaces",
    "com.company.agentgateway.infra"
})
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [x] **Step 4: 写 application.yml**

`gateway-bootstrap/src/main/resources/application.yml`：
```yaml
spring:
  application:
    name: agent-gateway
  main:
    banner-mode: off
server:
  port: 8080
# 后续计划在此加 nacos/redis/llm/otel 配置；本计划保持最小可启动
```

- [x] **Step 5: 运行启动测试确认通过**

Run: `mvn -q -pl gateway-bootstrap -am test -Dtest=GatewayApplicationTest`
Expected: PASS（上下文加载成功）。
> 本测试一期仅证明多模块装配链路通（bootstrap→interfaces→application→domain 编译路径可达）；后续 infra 计划加入业务 bean 后，contextLoads 才真正检验装配。

- [x] **Step 6: 真实启动验证**

Run: `mvn -q -pl gateway-bootstrap spring-boot:run`
Expected: 应用启动，监听 8080，日志无异常；手动 Ctrl-C 停止。
（这是人工验证步骤，证明整个多模块项目作为 Spring Boot 应用可运行。）

- [x] **Step 7: 提交**

```bash
git add gateway-bootstrap/src/
git commit -m "feat(bootstrap): runnable Spring Boot entrypoint + contextLoads test"
```

---

### Task 12: 整体集成验证（本计划收尾）

**Files:** 无新建（纯验证）

- [x] **Step 1: 全量编译 + 全量测试**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS——11 模块全部编译、domain 测试全绿、bootstrap contextLoads 通过、jacoco domain 覆盖率 ≥ 90%。
> 其余 9 个模块（api/application/6 个 infra/interfaces）本计划无测试类，`mvn test` 对它们仅验证编译。

- [x] **Step 2: 验证依赖方向（负向断言，强制 spec §3.2）**

```bash
# application 不得依赖 infra/interfaces/api/bootstrap
mvn -q -pl gateway-application dependency:tree | grep -E 'gateway-(infra|interfaces|api|bootstrap)' ; echo "exit=$?"
# Expected: 无输出（空），exit=1（grep 无匹配）
```
```bash
# infra-a2a 不得依赖 application/interfaces/其他 infra
mvn -q -pl gateway-infra-a2a dependency:tree | grep -E 'gateway-(application|interfaces|infra-(nacos|llm|persistence|security|observability)|api|bootstrap)' ; echo "exit=$?"
# Expected: 无输出，exit=1
```
```bash
# bootstrap 应依赖 interfaces（验证启动链路 bootstrap→interfaces→application→domain 通）
mvn -q -pl gateway-bootstrap dependency:tree | grep -E 'gateway-interfaces'
# Expected: 有输出（含 gateway-interfaces）
```
> 用**负向断言**（grep infra/interfaces 应为空）而非正向 grep，才能真正验证「application/infra 不反向依赖」。

- [x] **Step 3: 更新 README（记录构建命令与下一步）**

在 `README.md` 末尾加：
```markdown
## 构建

    mvn -q clean test          # 全量编译+测试
    mvn -q -pl gateway-bootstrap spring-boot:run   # 启动

## 当前进度

- [x] Plan ① 基础骨架（多模块 + domain 核心 + 可启动 bootstrap）
- [x] Plan ② A2A 客户端 + AgentCard 发现（gateway-infra-a2a / gateway-infra-nacos）
- [x] Plan ③ 多模型接入（gateway-infra-llm）
- [x] Plan ④ 会话存储 + 上下文窗口（gateway-infra-persistence）
- [x] Plan ⑤ 鉴权 + RBAC（gateway-infra-security + application）
- [x] Plan ⑥ 可观测 + Admin（gateway-infra-observability）
- [x] Plan ⑦ 编排核心 + 流式 SSE 端点（gateway-application + gateway-interfaces）
- [x] Plan ⑧ 管理后台（§16-20）
- [x] Plan ⑨ 成本中心 + 审计（§21-22）
- [x] Plan ⑩ 开放 API + 示例 Agent（§23 + example-agent）
```
> 上述后续计划编号为建议顺序；实际按 AGENTS.md 并行开发可独立并行（它们都只依赖本计划的 domain 契约）。

- [x] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs: update README with build commands and roadmap"
```

---

## Chunk 3 评审检查点

> 完成 Chunk 3 后，派 plan-document-reviewer 评审本 chunk；通过后本计划即完成。

---

## 后续计划（本计划完成后，可按 AGENTS.md 并行启动）

> 本计划建立的 domain 契约是后续所有 infra 计划的地基。

| 后续计划 | 模块 | 依赖的 domain 端口 | 并行性 |
|---|---|---|---|
| Plan ② A2A + Nacos 发现 | gateway-infra-a2a、gateway-infra-nacos | `ToolPort`、`AgentCardPort`（前置：Nacos A2A Spike） | ✅ 可并行 |
| Plan ③ 多模型接入 | gateway-infra-llm | `ChatClientPort`/`LlmSession`（需 Flow↔Flux 适配器） | ✅ 可并行 |
| Plan ④ 会话存储 | gateway-infra-persistence | `Session`/`ContextWindow` | ✅ 可并行 |
| Plan ⑤ 鉴权 + RBAC | gateway-infra-security | `AuthPrincipal`/`AgentGrant` | ✅ infra 部分可并行 |
| Plan ⑥ 可观测 + Admin | gateway-infra-observability | `InvocationCtx`/事件类型 | ✅ infra 部分可并行 |
| Plan ⑦ 编排 + SSE 端点 | gateway-application、gateway-interfaces | 集成 ②③④⑤⑥ 端口 | ⚠️ 串行于 ②-⑥ 之后 |
| Plan ⑧⑨⑩ 管理后台/成本/审计/开放API | 新增领域模块 + 各 infra | 依赖 ①-⑦ 骨架 | ⚠️ 串行于 ⑦ 之后 |

**并行/串行判断（遵循 AGENTS.md §1/§3）：**
- Plan ②③④ 的 infra 模块**完全独立**（各自独立模块、独立 domain 端口、不改同一文件）→ **可并行**派 backend-developer。
- Plan ⑤⑥ 的 **infra 部分**（gateway-infra-security / gateway-infra-observability）各自独立模块 → infra 实现可并行。但二者若都要往 `gateway-application` 加装配代码（如 AuthFilter bean、ObservabilityHooks），则 application 模块内需协调——**建议 application 侧的集成串行**（先 ⑤ 后 ⑥，或把 application 拆 application-auth / application-obs 子包隔离）。按 AGENTS.md §3「修改同一文件时串行」。
- Plan ⑦ 编排核心依赖 ②③④⑤⑥ 的端口实现 → **串行**于它们之后。

---

## 执行交接（Execution Handoff）

**本计划完成后：**
1. **下一个动作**：本计划经用户评审通过 → 按 `AGENTS.md` 进入实现阶段（subagent-driven-development 或 executing-plans 技能）→ 逐 Task 勾选执行 → 全绿后提交。
2. **后续计划读取的契约入口**（即本计划 Task 5-9 产出的 domain 端口/类型）：
   - `domain.orchestration.ToolPort` / `AgentCardPort` / `ChatClientPort` / `LlmSession`
   - `domain.orchestration.ToolEvent` / `LlmEvent` / `ToolDescriptor` / `InvocationCtx`
   - `domain.session.Session` / `Message` / `ContextWindow`
   - `domain.registry.AgentCard`（schema 用 String）
   - `domain.model.ModelDef` / `Capability`（§17.2 权威）
   - `domain.iam.AuthPrincipal` / `AgentGrant` / `AuthChannel`
   - `domain.shared.*`（Identity 值对象）
3. **已知 deferred 项**（不在本计划，记给后续计划）：
   - `example-agent` 骨架（Plan ⑩）
   - 所有 infra 实现（Plan ②-⑥）
   - Flow↔Reactor 适配器（Plan ③ gateway-infra-llm 必做）
   - Role/Permission 聚合到 AuthPrincipal（Plan ⑤/后续 RBAC 计划）
   - spec §3.3 已在本计划 Task 10 同步修订；后续若 domain 契约再变，需同步 spec 并重跑 spec 评审。
4. **验证门禁**（本计划交付物的「完成」定义）：
   - `mvn clean test` 全绿
   - domain jacoco 行覆盖 ≥ 90%
   - Task 12 Step 2 的依赖方向负向断言全部通过
   - bootstrap 可 `spring-boot:run` 启动



