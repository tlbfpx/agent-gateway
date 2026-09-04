# Design: 多模型接入（add-multi-model）

> 本 change 技术决策。详细 step 见后续 `writing-plans`。

> **实现约束提示**：domain 的 `ModelDef`（spec §17.2，已在 foundation 实现定稿）字段为：`ModelId id, String provider, String displayName, String endpoint, String apiKeyRef, Set<Capability> capabilities, int contextWindow, BigDecimal costPer1kIn, BigDecimal costPer1kOut, boolean enabled, List<String> tenantScope`，`Capability` 枚举为 `{FUNCTION_CALLING, VISION}`。本 change 必须按此实际签名装配，不得引入新的 ModelDef 变体。下文示例代码中的 provider 枚举/tenantScope 写法仅为说明思路，实现时以 domain ModelDef 为准（provider 是 String，按值分发；tenantScope 是 List<String>）。

> **本 design 已据 2026-08-13 LLM starter Spike（见 `docs/superpowers/spike/2026-08-12-saa-compat-report.md`）对齐实测 GAV**。Spring AI 2.0.0-M1 的 starter 命名规范从 1.x 的 `*-spring-boot-starter` 改为 `spring-ai-starter-model-*`，且 4 个 starter（deepseek/zhipuai/minimax/openai）全可用、质量优于 SAA。

## 1. Flow↔Flux 适配器（核心）

domain `LlmSession.generate()` 返回 JDK `Flow.Publisher<LlmEvent>`；Spring AI `ChatClient.call().stream()` 返回 Reactor `Flux<ChatResponse>`。infra 写适配器（foundation design.md 预告）：

```java
public final class ReactorFlowAdapter {
    public static Flow.Publisher<LlmEvent> adapt(Flux<ChatResponse> flux) {
        return subscriber -> {
            // onSubscribe(FlowSubscriptionAdapter)，把 Flow.Subscription.request/cancel
            // 桥接到 Reactor Subscription，背压透传，不在适配器内部缓冲
        };
    }
}
```
`ChatResponse` → `LlmEvent` 映射：含 toolCalls → `ToolCall(name,argsJson)`；finishReason 非 STILL_RUNNING → `Complete()`；否则 → `Delta(content)`。

## 2. ChatClientFactory（多 Provider 装配 + 缓存）
按 `ModelDef.provider()`（String）分发到对应 Spring AI ChatClient 构造。实际 starter GAV（Spike 已验证 Maven Central HTTP 200）：

| provider 值 | Starter GAV | 配置前缀 | 备注 |
|---|---|---|---|
| `dashscope` | `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope:2.0.0-M1.1` | `spring.ai.dashscope.*` | 需 exclude（见 §5） |
| `deepseek` | `org.springframework.ai:spring-ai-starter-model-deepseek:2.0.0-M1` | `spring.ai.deepseek.*` | 推荐专用 starter |
| `zhipuai` | `org.springframework.ai:spring-ai-starter-model-zhipuai:2.0.0-M1` | `spring.ai.zhipuai.*` | |
| `minimax` | `org.springframework.ai:spring-ai-starter-model-minimax:2.0.0-M1` | `spring.ai.minimax.*` | |
| `openai-compatible` | `org.springframework.ai:spring-ai-starter-model-openai:2.0.0-M1` | `spring.ai.openai.*` | 兜底方案（base-url 兼容 DeepSeek 等） |

Caffeine 缓存（key=ModelId，expireAfterAccess 1h，配置变更时 invalidate）。`apiKeyRef` 经 `${SECRET:XXX}` 解析为环境变量/外部密钥，缺失则启动快速失败。

## 3. ModelRegistry（Nacos 配置热更新）
监听 Nacos `agent-gateway-models.yaml`（dataId）→ 解析为 `Map<ModelId, ModelDef>` → AtomicReference 原子替换。变更时 diff 出受影响 ModelId，invalidate ChatClientFactory 对应缓存。yaml 中 capabilities 用 `FUNCTION_CALLING`/`VISION`（对齐 Capability 枚举），tenantScope 用列表（如 `[all]` 或 `["tenant-a"]`）。

yaml 示例（provider 为 String 值，对齐 §2 表）：
```yaml
models:
  - id: deepseek-v4-pro
    provider: deepseek
    displayName: DeepSeek V4 Pro
    endpoint: https://api.deepseek.com
    apiKeyRef: ${SECRET:DEEPSEEK_API_KEY}
    capabilities: [FUNCTION_CALLING]
    contextWindow: 128000
    costPer1kIn: 0.15
    costPer1kOut: 0.60
    enabled: true
    tenantScope: [all]
  - id: glm-4-plus
    provider: zhipuai
    displayName: GLM-4 Plus
    endpoint: https://open.bigmodel.cn
    apiKeyRef: ${SECRET:ZHIPU_API_KEY}
    capabilities: [FUNCTION_CALLING, VISION]
    contextWindow: 128000
    costPer1kIn: 0.05
    costPer1kOut: 0.05
    enabled: true
    tenantScope: [tenant-a, tenant-b]
```

## 4. 能力降级 Failover（§5.5.5，一期必做）
在 `ChatClientPortImpl.sessionFor(model, tools)`：若 `!tools.isEmpty() && !modelDef.supportsFunctionCalling()` → 切到 `fallbackToolModel`（配置项，启动校验其具备 FUNCTION_CALLING），日志记录降级。

## 5. dashscope exclude（Spike 已证必配）
SAA 2.0.0-M1.1 的 `DashScopeMultimodalEmbeddingAutoConfiguration` 类缺失（imports 引用但 jar 内不存在），Boot 4 严格校验致启动失败。配置：
```yaml
spring:
  autoconfigure:
    exclude:
      - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeMultimodalEmbeddingAutoConfiguration
```
**仅 dashscope 需要此 exclude**。其余 starter（deepseek/zhipuai/minimax/openai）Spike 已验证无 autoconfig 缺失问题，无需 exclude。

## 6. 密钥安全
`apiKeyRef` 形如 `${SECRET:DASHSCOPE_API_KEY}`。解析：识别前缀 → 提取环境变量名 → 读 System.getenv()；缺失抛 IllegalStateException。生产建议接 Vault/K8s Secrets（经环境变量注入），本 change 只做解析，不绑特定密钥后端。

## 6.1 依赖管理建议
引入 `spring-ai-bom:2.0.0-M1` 统一 Spring AI starter 版本（dashscope 走 SAA 自己的版本 2.0.0-M1.1）：
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0-M1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- dashscope 由 SAA 自己管理版本 -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
            <version>2.0.0-M1.1</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```
实际引入时：
```xml
<!-- Spring AI starters（版本由 BOM 管理） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-zhipuai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-minimax</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<!-- DashScope（显式版本） -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
```

## 7. 测试策略
- **单元**：ReactorFlowAdapter（Flow↔Flux 转换、背压、取消、映射）；ChatClientFactory（缓存命中/失效、Provider 分发）；ModelRegistry（yaml 解析、热更新原子性）；CapabilityFailover（触发/不触发）。
- **集成（WireMock）**：各 Provider Mock（SSE 流式响应），使用 Spike 验证的 GAV：
  - `spring-ai-alibaba-starter-dashscope:2.0.0-M1.1`（带 exclude）
  - `spring-ai-starter-model-deepseek:2.0.0-M1`
  - `spring-ai-starter-model-zhipuai:2.0.0-M1`
  - `spring-ai-starter-model-minimax:2.0.0-M1`
  - `spring-ai-starter-model-openai:2.0.0-M1`
  验证 LlmSession.generate 的 LlmEvent 序列 + ToolCall + Streaming。
- **覆盖率**：infra-llm ≥80%。

## 8. 与 domain 端口对接约束
严格遵循 add-foundation-skeleton 定稿签名（ChatClientPort/LlmSession/LlmEvent/ToolDescriptor/ModelDef），所有 Spring AI/Reactor 封装在 infra 内部，domain 零框架不被污染。

## 9. 实现期发现（运行期装配，2026-08-13 补）

bootstrap 接入 infra-llm 后，应用启动暴露了 5 个 AI starter 的真实运行期行为（计划/Spike 未覆盖）：

1. **各 AI starter autoconfig 无 key 时 fail-fast**：deepseek/openai/zhipuai/minimax/dashscope 每家的 autoconfig（chat/embedding/image/audio 等）在无 API key 时启动失败。本网关由 ChatClientFactory 按 ModelDef 按需构造 ChatModel（key 从 apiKeyRef 注入），**不依赖**这些自动装配 bean。
   - **解法**：bootstrap `application.yml` 给每个 provider 一个占位 key（`${PROVIDER_API_KEY:sk-placeholder}`）满足 presence 检查——key 只在真实 API 调用时校验，而本网关不调用这些 bean。部署时用真实 key 覆盖。
   - dashscope 那个打包缺陷类（DashScopeMultimodalEmbeddingAutoConfiguration，jar 内不存在）仍必须 exclude（占位 key 解决不了缺类）。
2. **条件装配**：`InfraLlmAutoConfiguration` 用 `@ConditionalOnProperty(nacos.addr)`——无 nacos.addr（开发态/CI）时不装配 ModelRegistry/ChatClientPort（这些需真实 Nacos），应用空启动；配 nacos.addr（部署）才接真实 Nacos 全链路。
3. **裸 nacos-client**（非 spring-cloud-alibaba，后者无 Boot4 版本）：`NacosConfigService(Properties)` 构造，`@Bean` 方法声明 `throws NacosException`。

这些是运行期现实，未来接其他 starter（如 observability）也可能遇类似 fail-fast，同样用占位配置/条件装配处理。

