# B:FC 工具注入修复 + 运行时韧性设计文档

- **日期:** 2026-08-19
- **状态:** 已评审(用户逐节确认)
- **范围:** FC 工具注入修复(前置)+ A2A 重试 + 熔断(Resilience4j)+ Agent 多实例负载均衡
- **前置:** A(可观测性深化)已交付 — 熔断指标复用其 metrics_samples/告警体系

## 1. 背景

A 收尾验证发现:example-agent 直连调通、网关发现 echo-agent 正常,但 **LLM 从不发起 tool call**。根因:`ChatClientLlmSession.generate` 里 `new Prompt(messages)` 从未把 tools 传给 ChatModel —— 工具描述构造了、RBAC 过滤了、toolCalls 解析也在,但模型不知道工具存在。这是先于韧性的功能断点:没有真实 Agent 调用,重试/熔断/负载均衡无从生效,agent.call span 也永不产生。

同时 A2A 调用仅有 Reactor timeout(30s),无重试、无熔断、单实例 endpointUrl(Nacos 多实例信息在 AgentCardMapper 映射时被丢弃)。

### 1.1 已确认决策

| 决策点 | 结论 |
|---|---|
| 范围 | 全量 B(FC 修复 + 重试/熔断/多实例) |
| 回填格式 | 原生 ToolResponseMessage(严格厂商协议兼容) |
| 熔断实现 | Resilience4j(~2MB,官方 reactor 集成) |
| 重试策略 | 仅幂等错误(连接失败/502/503/504),退避 3 次 100/200/400ms + 50% jitter;SSE 出流后不重试 |
| 负载均衡 | 多实例 RoundRobin + 失败转移(AgentCard.endpointUrls) |
| 熔断可观测 | 接 A 指标体系(Micrometer → metrics_samples → Dashboard/告警) |

## 2. 总体架构与模块边界

```
ChatOrchestrator.runToolLoop(不改循环结构)
  │
  ▼
ChatClientPortImpl.sessionFor(model, tools)
  │
  ▼
ChatClientLlmSession.generate          ← 【修复点:FC 注入】
  │  new Prompt(messages, ToolCallingChatOptions)
  │    └─ ToolDescriptor → ToolCallback(仅 schema 声明,不内置执行)
  │    └─ internalToolExecutionEnabled(false) ← 保住自研工具循环
  │  工具结果回填:ToolResponseMessage(原生协议格式)
  ▼
LlmEvent.ToolCall(现有解析链路)
  │
  ▼
executeToolCalls → A2aToolPort.invoke
  │
  ▼
ResilientA2aClient(新装饰器,包住现有 A2aClient)
  ├─【重试】Flux.retryWhen(Retry.backoff(3,100ms).jitter(0.5).filter(isRetryable))
  ├─【熔断】Resilience4j CircuitBreaker(按 agentName 粒度)
  └─【多实例】AgentCard.endpointUrls + RoundRobin + 失败转移
```

模块归属:

| 变更 | 模块 |
|---|---|
| ToolDescriptor→ToolCallback 转换、Prompt 构造、ToolResponseMessage 回填 | gateway-infra-llm |
| ResilientA2aClient、Resilience4j 装配、重试/熔断逻辑 | gateway-infra-a2a |
| AgentCard.endpointUrls 扩展、RoundRobinSelector | gateway-domain |
| Nacos Mapper 采集全实例 | gateway-infra-nacos |
| 指标白名单加 a2a. 前缀 | gateway-infra-observability |

**明确不做**:不改 ChatOrchestrator 循环结构、不做并发工具调用(保持串行)、不做实例级熔断与健康探测摘除(下轮)。

## 3. FC 工具注入

### 3.1 ChatClientLlmSession.generate 改造

```java
// 现状(断裂点)
var flux = chatModel.stream(new Prompt(messages));
// 改造后
var flux = chatModel.stream(new Prompt(messages, buildToolOptions(tools)));
```

buildToolOptions 逻辑:

1. tools 为空 → 返回 null(Prompt 退化为两参构造,行为与现状一致)
2. 非空 → ToolCallingChatOptions:每个 ToolDescriptor → 仅声明 schema 的 ToolCallback(不绑执行函数);`internalToolExecutionEnabled(false)` 防双重执行
3. inputSchemaJson 解析失败 → 跳过该工具 + WARN(不阻断其他工具)

### 3.2 工具结果回填改为原生协议

- 模型上一轮发起过 toolCall 时回填 Spring AI 的 ToolResponseMessage(携带 toolCallId 对应)
- 纯文本转换保留给无 FC 厂商降级
- MiniMax/DeepSeek(OpenAI 兼容协议)原生支持;ZhiPu 由 Spring AI adapter 兜底

### 3.3 流式 toolCall 聚合(顺手修复)

LlmFlowAdapter 现状每帧只取 toolCalls.get(0),流式分片参数会丢。改造:按 toolCallId 聚合分片 arguments,完整后发一个 LlmEvent.ToolCall。

### 3.4 改动文件与测试

| 文件 | 变更 |
|---|---|
| ChatClientLlmSession | Prompt 构造 + options + ToolResponseMessage 回填 |
| 新增 ToolCallbackConverter | ToolDescriptor → ToolCallback,含 schema 解析 |
| LlmFlowAdapter | toolCall 分片聚合 |
| ChatClientLlmSessionTest | ArgumentCaptor 断言 Prompt options 携带 tools、internalToolExecutionEnabled=false |
| 新增 ToolCallbackConverterTest | schema 解析表驱动(正常/畸形/空) |

关键约束:工具必须每次请求级放 Prompt 上,不能烘进按 ModelId 缓存共享的 ChatModel 实例(会串会话)。

## 4. A2A 韧性层

### 4.1 ResilientA2aClient(装饰器)

```
invokeStream(card, argsJson):
  1. RoundRobin 选实例
  2. 熔断检查(agentName 粒度):OPEN → Error("A2A_CIRCUIT_OPEN")
  3. 内层 invokeStream(url, name, args)
       .transform(CircuitBreakerOperator)
       .retryWhen(Retry.backoff(3,100ms).jitter(0.5).filter(isRetryable))
  4. 实例失败(重试耗尽)→ 切下一实例(最多全部)
  5. 全失败 → Error("A2A_ERROR")
```

重试语义:isRetryable = 连接拒绝/502/503/504;不重试 400/401/403/404/业务错误。SSE 出流后(首个 onNext)错误走 onErrorResume 不重试(Retry 仅在订阅期错误触发,天然满足)。退避 100→200→400ms,jitter 50%。

### 4.2 熔断配置(按 Agent 粒度)

| 参数 | 默认 | 配置键 |
|---|---|---|
| slidingWindowSize | 20 | a2a.circuit.window-size |
| failureRateThreshold | 50% | a2a.circuit.failure-rate |
| waitDurationInOpenState | 30s | a2a.circuit.wait-open-seconds |
| permittedCallsInHalfOpen | 3 | a2a.circuit.half-open-calls |

熔断 key = agentName(多实例共享;实例级下轮)。指标(A 体系):

- a2a.circuit.state(Gauge,0/1/2)
- a2a.circuit.shortcircuited(Counter)
- a2a.retry.exhausted(Counter,按 agent 标签)

→ PgMetricsPublisher 白名单加 a2a. 前缀自动落库 → Dashboard/告警可配「熔断开启告警」。

### 4.3 AgentCard 多实例扩展

```java
public record AgentCard(..., String endpointUrl,      // 保留:首选实例(旧契约)
                        List<String> endpointUrls) {  // 新增:全实例
    // 紧凑构造器:endpointUrls null/空 → [endpointUrl]
}
```

NacosAgentCardMapper 采集全部实例 url;DevStub echo-agent 配 localhost:8090;RoundRobinSelector(domain 纯逻辑)。

### 4.4 装配

```
InfraA2aAutoConfiguration:
  CircuitBreakerRegistry(a2a.circuit.* 覆盖默认)
  A2aClient(内层)
  @Primary ResilientA2aClient
  a2a.retry.max-attempts=3 / a2a.retry.backoff-ms=100
```

### 4.5 测试策略

| 测试 | 方式 |
|---|---|
| 重试语义 | WireMock stub 场景序列(5xx 重试成功/4xx 不重试/退避次数) |
| 熔断状态机 | WireMock + 连续失败→OPEN→快速失败→半开恢复 |
| 多实例切换 | WireMock 双端口 |
| RoundRobin | 纯逻辑单测 |
| E2E | example-agent(8090)+ PG:agent.call span 落库 + 指标 + 熔断指标全链路 |

覆盖率:infra-llm/infra-a2a/domain 均 0.80 jacoco 门禁维持。

## 5. 成功标准

1. LLM 真实发起 tool call(example-agent 被真实调用,响应回填对话)
2. agent.call span 落库 + A2A 指标出现在 metrics_samples(A 的 trace/趋势页可见)
3. 模拟 5xx:重试 3 次后成功,trace 可见重试耗时
4. 连续失败触发熔断:快速失败 + a2a.circuit.shortcircuited 计数 + 告警规则可配
5. 双实例:实例 1 挂→自动切实例 2,对话不中断
6. verify.sh 全绿
