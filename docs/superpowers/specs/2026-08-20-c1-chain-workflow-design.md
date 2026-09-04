# C1:显式链式多 Agent 编排(Chain)设计文档

- **日期:** 2026-08-20
- **状态:** 已评审(用户逐节确认)
- **范围:** 显式多 Agent 编排 C 阶段 1 —— 链式 Chain(YAML/JSON DSL + JSONPath 映射 + 同步执行 + 新聚合根 WorkflowRun);并完成 B 阶段推迟的 toolCallId 原生 ToolResponseMessage 回填修复
- **前置:** A(可观测)+ B(FC注入 + 韧性)已交付

## 1. 背景与目标

当前编排 ChatOrchestrator.runToolLoop 让 LLM 自决多 tool call:灵活但不可控、不可审计、不适合业务流水线(RAG→总结→格式化输出等)。A2A 协议与 Nacos 注册的 Agent 已稳定,现需**显式工作流**让业务方能描述确定性多 Agent 链路。

C 阶段 1(C1)只做 Chain(链式),后续 C2 Parallel、C3 Switch、C4 嵌套 DAG 排期。

### 1.1 已确认决策

| 决策点 | 结论 |
|---|---|
| 首阶段范围 | C1 Chain(线性 workflow) |
| DSL 形态 | YAML/JSON |
| 入参映射 | JSONPath |
| 持久化粒度 | 新聚合根 WorkflowRun(独立 Session) |
| Workflow ↔ Session | 独立 Session(WorkflowRun 启动时新建) |
| 暴露 LLM 作为 Tool | 否(避免自决 vs 显式冲突) |
| toolCallId 原生回填 | 一并升级(完成 B 阶段 1 注释推迟的修复) |
| API 同步/异步 |同步返回 |

## 2. 总体架构

```
POST /v1/workflows/run  (body: { definition, inputs })
                                      │
                                      ▼
              WorkflowParseService.parse(body)  → WorkflowDef
                                      │
                                      ▼
            WorkflowOrchestratorImpl.run(def, inputs, ctx)
                                      │
       (同步循环:Step1 → Step2 → ... → StepN)
                                      │
   ┌───── Step i: StepExecutor.execute(StepDef) ──────┐
   │ 1. JsonPathResolver.resolve(stepDef.inputs, ctx) │
   │ 2. tracer.span("workflow.step", CLIENT, attrs)    │
   │ 3. ToolPort.invoke(agentCard, argsJson, ctx)      │ ← B 韧性层
   │ 4. 收集 ToolEvent 流 → StepRun(outputs={...})    │
   └──────────────────────────────────────────────────┘
                                      │
                                      ▼
                       WorkflowRun(状态/各 step 输出)
                                      │
                                      ▼
            { "workflowRunId", "status", "outputs": {...}, "steps": [...] }
```

**与现有 runToolLoop 关系:** **并存**,独立入口 `/v1/workflows/run`,workflow 不作为 Tool 暴露给 LLM。共享 ToolPort / ResilientA2aClient / AgentCardPort / GatewayTracer / ObservabilityHooks。

## 3. 数据模型(domain 零框架)

### 3.1 WorkflowDef / StepDef

```java
public record WorkflowDef(
    String name,
    List<StepDef> steps,
    Map<String, Object> defaultInputs
) {}

public record StepDef(
    String name,                              // 同一 workflow 内唯一
 String agent,                             // AgentCard.name
    Map<String, JsonPathExpression> inputs,   // input key → JSONPath
    Integer timeoutMs                         // 单步超时,可空(默认 30000)
) {}

public record JsonPathExpression(String raw) {  // raw 字符串(如 "$.inputs.x")
    public Object resolve(Map<String, Object> ctx) { ... }  // 由实现处理
}
```

### 3.2 WorkflowRun / StepRun(新聚合根)

```java
public record WorkflowRun(
    String runId,
    String workflowName,
 Status status,                // RUNNING / COMPLETED / FAILED
    Instant startedAt,
    Instant finishedAt,
    Map<String, Object> outputs,
    List<StepRun> steps
) {
    public enum Status { RUNNING, COMPLETED, FAILED }
}

public record StepRun(
    String name,
 Status status,
    Map<String, Object> inputs,
    Map<String, Object> outputs,
    Long durationMs,
    String errorMessage
) {
    public enum Status { RUNNING, COMPLETED, FAILED }
}
```

### 3.3 扩展 ToolResultMessage(B 推迟项一并落地)

```java
public record ToolResultMessage(String agentName, String content, boolean slimmed, String toolCallId)
 implements Message {}
```

- ChatClientLlmSession.toSpringMessage:toolCallId 非空 → ToolResponseMessage(原生),否则文本降级
- ChatOrchestrator.executeToolCalls:从 Spring AI `AssistantMessage.ToolCall.id()` 回填 toolCallId(LlmFlowAdapter 累积容器加 id 字段)
- 现有 PG/InMemory Session 反序列化兼容(默认 null)

### 3.4 新增 domain 端口

```java
public interface WorkflowRepository {
    WorkflowRun save(WorkflowRun run);
    Optional<WorkflowRun> find(String runId);
}

public interface WorkflowOrchestrator {
    WorkflowRun run(WorkflowDef def, Map<String, Object> inputs, InvocationCtx ctx);
}

public interface JsonPathResolver {
    Object resolve(String jsonPath, Map<String, Object> context);
}
```

## 4. 组件实现(分层归属)

| 层 | 新增/变更 |
|---|---|
| domain | WorkflowDef / StepDef / JsonPathExpression / WorkflowRun / StepRun / WorkflowRuntimeException / WorkflowRepository / WorkflowOrchestrator / JsonPathResolver 端口;ToolResultMessage 加 toolCallId 字段 |
| application | WorkflowOrchestratorImpl / WorkflowParseService / StepExecutor |
| infra | JaywayJsonPathResolver(jayway json-path)/ InMemoryWorkflowRepository |
| interfaces | AdminWorkflowController(POST /v1/workflows/run / GET /v1/workflows/{runId}) |
| observability | GatewayTracer 新增 workflow.run / workflow.step span;PgMetricsPublisher 白名单加 `workflow.` 前缀 |

## 5. 核心循环逻辑

```
for each stepDef in def.steps:
    try:
        ctx.advanceStep(stepIndex)
        Map inputs = jsonPathResolver.resolve(stepDef.inputs, ctx)
        tracer.span("workflow.step", CLIENT, {workflow.name, step.name, step.index})
        StepRun stepRun = stepExecutor.execute(stepDef, inputs, ctx)
        ctx.put("steps." + step.name + ".outputs", stepRun.outputs())
    catch WorkflowRuntimeException e:
        stepRun.status = FAILED; stepRun.errorMessage = e.message
        WorkflowRun.status = FAILED; return     // fail-fast
    finally:
        workflowRun.steps.add(stepRun)
WorkflowRun.status = COMPLETED
return workflowRun
```

## 6. 错误处理与降级

| 错误 | 处理 |
|---|---|
| DSL 解析失败 | 400 校验报错带 step索引与字段路径 |
| Agent 未注册 | StepRun.FAILED + error="agent not registered: X" |
| JSONPath 引用失败 | StepRun.FAILED + error="context not satisfied: $.steps.X.outputs.Y" |
| ToolEvent.Error | StepRun.FAILED + error 透传;A2aClient 韧性错误已转 ToolEvent.Error("A2A_CIRCUIT_OPEN" / "A2A_ERROR") |
| 输出非 JSON | 警告 + outputs={"text": <raw>}降级,后续 JSONPath 引用会失败(清晰信号) |
| 失败模式 | fail-fast 立即终止(下轮可扩 strict / continue) |

## 7. 测试策略

| 层 | 测试方式 |
|---|---|
| domain: JsonPathExpression | $.inputs.x / $.steps.X.outputs.y 解析 + 空上下文异常 |
| domain: WorkflowRun / StepRun | record 字段、状态枚举 |
| domain: WorkflowRuntimeException | message + step index 携带 |
| application: WorkflowOrchestratorImpl | Mock ToolPort + AgentCardPort + JsonPathResolver;WireMock 顺序验证 step 调用 |
| application: WorkflowParseService | YAML/JSON 双格式解析 + 字段校验 |
| infra: JaywayJsonPathResolver | 真实 jsonpath 表达式 + 嵌套 |
| interfaces: AdminWorkflowController | MockMvc + Mock 仓储 + 4xx/5xx 路径 |
| E2E | 真实 example-agent(8090)启动 echo-agent + workflow.yaml 含 chain + 验证 WorkflowRun.status=COMPLETED |

## 8. 端点契约

```
POST /v1/workflows/run
  body: { "definition": {...}, "inputs": {...} }
  200: WorkflowRun
  4xx: 校验错误
  5xx: 步骤失败 → WorkflowRun.status=FAILED(仍 200,status 字段表达失败)

GET /v1/workflows/{runId}
  200: WorkflowRun
  404: not found

POST /v1/workflows/parse     (可选 dryRun,只校验不执行)
  body: WorkflowDef
  200: { valid: true } | { errors: [...] }
```

## 10. 成功标准

1. 同步 POST 返回完整 WorkflowRun,每 step 含输入快照/输出/耗时/状态
2. Chain 顺序执行(trace 可见)
3. JSONPath 解析:$.inputs.* 与 $.steps.*.outputs.*
4. 任一 step 失败 → WorkflowRun.FAILED,后续 step 不执行
5. 复用 B 韧性:ToolPort.A2aCall 错误自动走熔断/重试/多实例
6. 复用 A 观测:trace / 指标 / 告警自然覆盖 workflow 执行
7. toolCallId 一并升级:有原生协议时返回 ToolResponseMessage,无则文本降级
8. verify.sh 全绿