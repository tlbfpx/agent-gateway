# C2:并行多 Agent 编排(Parallel Workflow)设计文档

- **日期:** 2026-08-20
- **状态:** 已评审(用户逐节确认)
- **范围:** C2.1 基础并行 + JoinAll + C2.3 parallel 嵌套 Chain
- **前置:** C1 Chain 已交付(2026-08-19 spec 4)

## 1. 背景与目标

C1 Chain 是线性流水线(RAG → 总结 → 翻译)。**多源检索/多模型 fan-out** 是更常见的业务场景:
- 性能:多源并行(3 个 Agent 同时跑,延迟从 3x → 1x)
- 鲁棒性:部分失败可降级(本期 JoinAll 严格;JoinAny/JoinMajority 留到 C2.2)
- 灵活性:每分支独立配置 agent / inputs / timeout

C2 提供并行 fan-out,**保留 C1 Chain 全部能力**,与 C1 同构、协同,后续可演进到 C2.2(其他 join 模式)/ C2.3 已含(嵌套)。

## 2. 总体架构

```
POST /v1/workflows/run
  body: { definition: {name, steps:[..., {parallel:{...}}, ...], ...} }
                              │
                              ▼
              WorkflowOrchestratorImpl.run(def, inputs, ctx)
                              │
              ┌──────────── sequential for each step ─────────────┐
              │                                                  │
              ▼                                                  │
  step is Single  → StepExecutor.executeSingle (C1 路径)        │
              │                                                  │
              ▼                                                  │
  step is Parallel → ParallelExecutor.executeParallel           │
                       │  (Reactor Mono.zip)                      │
                       ├── Branch1: StepExecutor.executeSingle    │
                       ├── Branch2: StepExecutor.executeSingle    │
                       └── Branch3: StepExecutor.executeSingle    │
                              │  JoinAll 严格                     │
                              ▼                                  │
                    Map{"outputs": [<b1>, <b2>, <b3>],            │
                         "errors":  [] | [<e1>] }                │
                              │                                  │
                              ▼ (runtimeContext 写入)            │
              │                                                  │
              └────── next step 继续引用 $.steps.X.outputs[*] ──┘
```

## 3. 数据模型

### 3.1 WorkflowStep(sealed union)

```java
public sealed interface WorkflowStep {
    /** 序列步骤(C1 StepDef 包装) */
    record Single(StepDef def) implements WorkflowStep {}
    /** 并行节点(本期) */
    record Parallel(ParallelDef def) implements WorkflowStep {}
}

public record ParallelDef(
    String name,                            // workflow 内唯一
 Join join,                            // JOIN_DEFAULT = ALL
    List<BranchDef> branches) {}

public enum Join { ALL, ANY;                       // MAJORITY 留到 C2.2
    public static Join of(String s) { ... }
}

public record BranchDef(
    String name,                            // parallel 内唯一(span attribute)
 String agent,
    Map<String, JsonPathExpression> inputs,
    Integer timeoutMs) {}
```

### 3.2 WorkflowDef 调整

```java
public record WorkflowDef(
    String name,
    List<WorkflowStep> steps,                // 之前 List<StepDef>,改为 WorkflowStep 列表
    Map<String, Object> defaultInputs) {}
// 注:C1 现有 YAML 中 step 形态直接对应 WorkflowStep.Single;后向兼容
// WorkflowParseService 已有的 validate() 校验 steps[] name 唯一、agent 非空,
// C2.1 同步:支持 parallel 节点校验(branches.name 唯一)。
```

### 3.3 StepRun 扩展(parent/branch 标识)

```java
public record StepRun(
    String name,
 StepRun.Status status,
    Map<String, Object> inputs,
    Map<String, Object> outputs,
    Long durationMs,
    String errorMessage,
    Integer parentIndex,        // ← 新增:parallel 内兄弟节点
    Integer branchIndex,        // ← 新增:branch 在 parallel 内 0-based 索引
    String branchName) {         // ← 新增:对应 ParallelDef.branches[i].name
    // 紧凑构造器(给 C1 用)
    public StepRun(String name, StepRun.Status status, Map<String, Object> inputs,
                   Map<String, Object> outputs, Long durationMs, String errorMessage) {
        this(name, status, inputs, outputs, durationMs, errorMessage, null, null, null);
    }
}
```

### 3.4 新端口:WorkflowStepExecutor

```java
public interface WorkflowStepExecutor {
    /** C1:执行单步 */
    StepRun executeSingle(InvocationCtx ctx, StepDef step, Map<String, Object> inputs,
                            String workflowName, int stepIndex);
    /** C2:执行并行节点 */
    ParallelResult executeParallel(InvocationCtx ctx, ParallelDef parallel,
                                    Map<String, Object> inputs,
                                    String workflowName, int stepIndex);
}

public record ParallelResult(
    List<StepRun> branches,            // 全部(含失败)
    Map<String, Object> outputs,       // 合并:Map.of("outputs", List<...>, "errors", List<...>)
    long durationMs,
    String firstError) {}
```

## 4. 组件实现

| 层 | 新增 / 改动 |
|---|---|
| domain | `WorkflowStep`(sealed)+ `ParallelDef`/`BranchDef`/`Join`;`StepRun` 增 3 字段(向后兼容);`WorkflowStepExecutor` 端口 |
| application | `ParallelExecutor`(新);`WorkflowOrchestratorImpl` 改造 step dispatch(single vs parallel);trace 接入 |
| infra | 无新文件(复用 A2A / Resilient) |
| interfaces | 无改动 |

**Parallel 执行核心**(ParallelExecutor):

```java
public ParallelResult executeParallel(InvocationCtx ctx, ParallelDef parallel,
                                     Map<String, Object> inputs, String workflowName, int stepIndex) {
    long start = System.currentTimeMillis();
    // 1. 启动 parent span(name="workflow.parallel")
    // 2. 顺序:branches list
    List<Mono<StepRun>> monos = parallel.branches().stream().map(b -> {
        // 启动 child span(attributes 含 branch_name/agent/index)
        return Mono.fromCallable(() -> 
            stepExecutor.executeSingle(ctx, new StepDef(b.name(), b.agent(), b.inputs(), b.timeoutMs()),
                                         inputs, workflowName, stepIndex))
            .subscribeOn(Schedulers.parallel())
            .doOnError(...)  // child span 标 ERROR
            .onErrorResume(e -> Mono.just(StepRun.FAILED(...)));   // 失败不中断
    }).toList();
    // 3. zip 等待全部
    List<StepRun> branches = Mono.zip(monos, a -> a).block();
    // 4. 拼 ParallelResult.outputs
    Map<String, Object> outputs = Map.of(
        "outputs", branches.stream().map(StepRun::outputs).toList(),
        "errors",  branches.stream().map(StepRun::errorMessage).filter(java.util.Objects::nonNull).toList()
    );
    String firstError = branches.stream()
        .filter(b -> b.status() == StepRun.Status.FAILED)
        .map(StepRun::errorMessage).findFirst().orElse(null);
    return new ParallelResult(branches, outputs, System.currentTimeMillis() - start, firstError);
}
```

**JSONPath 跨步**:`$.steps.<parallelName>.outputs[*]` 返 list;下游 step 的 inputs JSONPath 直接 `.outputs[*]` 拿(可能需要 `$[*]` 或在 jayway 中用 `$..outputs[*]` 兜底;Jayway 2.9 `$[*]` 测试通过)。

**trace span**:
- parent: `name="workflow.parallel"`, attributes `{workflow, name, step_index, branch_count}`
- branch: `name="workflow.parallel.branch"`, attributes `{branch_name, agent, branch_index}`,share `traceId` 但独立 `spanId`
- 失败 branch 的 span.status=ERROR 携带 error message

## 5. 错误处理与降级

| 场景 | 处理 |
|---|---|
| 任一 branch 失败 | JoinAll 严格 → ParallelResult.firstError ≠ null → WorkflowRun.status=FAILED + partial result(branches 全记录)落库 |
| Branch timeout | 该 branch status=FAILED;parallel parent max(branches.timeoutMs) |
| Branch 熔断 OPEN(per-agent) | fast fail → branch status=FAILED;其他正常分支仍完成 |
| Agent 未注册 | `agent not registered: x` → branch FAILED → JoinAll 整体失败 |
| JSONPath `[*]` 解析失败 | WorkflowRuntimeException(`JsonPath context not satisfied`)→ 整体 FAILED |
| Reactor zip 异常 | 单 branch 异常被 `onErrorResume` 转 StepRun.FAILED,其他分支继续(zip 默认不等失败,需 `Flux.merge` 而非 `Mono.zip` —— 评审时已确认) |

**Reactor zip vs merge 关键决定**:
- `Mono.zip` 遇任一错误立即终止;若用 `Flux.merge` 则等所有,但需手工等
- 实际选 `Flux.merge` + `Flux.reduce` 等待所有(joinAll 等全部);这才能 partial result 完整

## 6. 测试策略

| 测试 | 方式 |
|---|---|
| domain:ParallelDef / WorkflowStep | record 字段、Join 枚举、sealed 完备 |
| domain:StepRun 扩展 | record 字段;向后兼容(7 参构造器 → 10 参 + 紧凑构造器兼容) |
| application:ParallelExecutor(核心) | 3 分支并行 + JoinAll 全成功;1 分支失败 → 整体 FAILED + 仍记录其他成功;全失败;Branch name 唯一性;JSONPath `[*]` 解析 |
| application:JSONPath `[*]` 验证 | jayway 真实解析 `$.steps.p.outputs[*]` 返 list |
| application:trace span | mock Tracer 验证 parent + N child span 触发 + 属性 |
| application:嵌套 chain | parallel.branches[i] = Single(Chain) → 嵌套执行 |
| interfaces:POST YAML 含 parallel | MockMvc → orchestrator 收到 ParallelDef → run() |
| E2E | example-agent(8090)+ workflow yaml 3 分支 parallel + JSONPath `[*]` 下游引用 |

## 7. 端点契约(无变化)

C1 端点已支持:`POST /v1/workflows/run` 接受 JSON / YAML body,WorkflowParseService 解析;
新增 WorkflowStep.Single 与 Parallel 的 Jackson 反序列化。

## 8. 成功标准

1. 3 分支 parallel chain,总耗时 ≈ max(单分支),不是 sum
2. JoinAll 任一分支失败 → 整体 FAILED + partial 落库
3. 嵌套 parallel 嵌套 chain 工作正常
4. trace spans:parallel parent + 3 child span,同 traceId
5. JSONPath `$.steps.p.outputs[*]` 返回 list
6. verify.sh 全绿 + UI 测试 + E2E 真实链路

## 9. 交付清单

- `WorkflowStep` sealed + `ParallelDef` + `BranchDef` + `Join` enum
- `StepRun` 扩展 3 字段
- `WorkflowStepExecutor` 端口
- `ParallelExecutor` 实现
- `WorkflowOrchestratorImpl` 改造 step dispatch
- 4-5 单测 + 1 jayway 验证测试
- verify.sh 全绿

## 10. 不在本期

- JoinAny / JoinMajority(C2.2)
- Per-branch 重试(下轮)
- parallel span 持久化到 PG(本期 in-memory 标记足够)
- 嵌套 chain 输出 schema(spec C2.3 复杂)
