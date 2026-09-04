# C3:Switch 节点 + 嵌套端到端设计文档

- **日期:** 2026-08-21
- **状态:** 已评审(用户逐点确认)
- **范围:** C3 Switch 节点(分路由由)+ 嵌套 workflow 真实端到端

## 1. 背景

C1 Chain 线性,C2 Parallel 多源 fan-out。实际业务中**分路由**(工单按类型路由)更普遍。
C3 Switch 节点:基于上一步 outputs 字段值,选择执行哪个后续 step。Switch 与 Chain/Parallel **可任意嵌套**。

本期并发完成**嵌套真实端到端测试**(chain ⊃ parallel ⊃ chain 等),跑真实 example-agent。

## 2. 总体架构

```
WorkflowDef.steps[] = [
  ChainStep: 线性 Step1 → Step2 → ...
  ParallelStep: Branch1 || Branch2 || Branch3 (C2.1)
  SwitchStep: 评估 cases 表达式 → 匹配 → 执行对应 step 块
]
```

SwitchStep 结构:有 N 个 case (when key+value, target step) + 1 default step(必有)。

## 3. DSL

```yaml
steps:
  - name: router
    agent: router-agent
    inputs: { q: "$.inputs.q" }
  - switch:
      key: $.steps.router.outputs.intent       # JSONPath
      cases:
        - { value: billing,  step: { name: billing-step,  agent: billing-agent,  inputs: { ticket: $.steps.router.outputs.ticket } } }
        - { value: tech,      step: { name: tech-step,      agent: tech-agent,      inputs: { ticket: $.steps.router.outputs.ticket } } }
      default:
        name: fallback-step
        agent: general-agent
        inputs: { ticket: $.steps.router.outputs.ticket }
```

- `key` 是 JSONPath,执行时取上一步 outputs 字段值
- `cases[].value` 是字面量(字符串/数字/布尔)
- `cases[].step` 是 Single StepDef(同 C1 形态)
- `default` 是 Single StepDef(必有,无 → 启动校验报错)
- 匹配顺序:cases 顺序(第一条匹配即选);都不匹配 → default
- 嵌套:case step 内部仍可包含 `parallel` 节点

## 4. 数据模型

```java
public record CaseDef(String value, StepDef step) {}    // 字面量 + 单步
public record SwitchDef(
    String name,
    JsonPathExpression key,                          // $.steps.X.outputs.<k>
    List<CaseDef> cases,
    StepDef defaultStep) {}                          // 必有

public sealed interface WorkflowStep {
    record Single(StepDef def) implements WorkflowStep {}
    record Parallel(ParallelDef def) implements WorkflowStep {}
    record Switch(SwitchDef def) implements WorkflowStep {}        // C3 新增
}
```

## 5. 执行语义

```
For each SwitchStep:
  1. 解析 key: jsonPathResolver.resolve(key, runtimeContext) → Object
  2. 顺序遍历 cases:
     - case.value.equals(key) 命中 → 执行 case.step(写入 runtimeContext, 后续 step 继续)
     - 命中后该 SwitchStep 完成
  3. 都不匹配 → 执行 defaultStep(必有)
  4. 失败/异常 → throw WorkflowRuntimeException
```

## 6. trace span 结构(已确认 parent + 单 child)

- parent: `name="workflow.switch"`,attributes `{workflow, name, key, case_count, selected_case}`
- child: `name="workflow.switch.case"`,attributes `{selected_case, value, agent}`,branches 行为同 Chain(同 traceId)
- 未选中的 case 不创建 span(简洁,符合"parent + 单 child")

## 7. 与现有节点的关系

- **嵌套**:Switch 节点的 case.step 可包含 Parallel / 另一个 Switch / 任意 StepDef
- **不可与 Parallel 嵌套本身**:Parallel.branches[].def 是 BranchDef,**不支持 WorkflowStep**。**注意**:这与 C2.1 一致(spec C2.1 parallel.branches 是单步非嵌套)
- C4(嵌套 DAG)是后续,本期 Switch/Parallel/Chain 三种节点的 case step 都可继续嵌套(因为 case.step 是 StepDef 即可继续 WorkflowStep)

## 8. 嵌套端到端测试

启动 example-agent(8090)与后端(PG 模式),跑嵌套 yaml,验证:

1. **Chain ⊃ Parallel ⊃ Chain**:顶层 Chain,Step1 (echo) → Parallel (Step2a/Step2b) → Step3 (echo)
2. **Chain ⊃ Switch**:Step1 (echo 输出含 intent 字段) → Switch (按 intent 路由到 Step2a/Step2b) → Step3
3. **持久化**:WorkflowRun + 多个 StepRun 写入 PG,跨重启可查
4. **outputs 跨步引用**:$.steps.X.outputs.<k> 解析正确
5. **trace 落库**:metrics_samples 含 workflow.run.* 计数

## 9. 端点契约(无变化)

- `POST /v1/workflows/run` body: WorkflowDef JSON(支持 chain + parallel + switch 节点)
- `GET /v1/workflows/{runId}` 返回完整 WorkflowRun(steps 列表)
- 嵌套测试加 IntegrationTest 套件(`@SpringBootTest` mock example-agent stub)— 不依赖外部服务

## 10. 成功标准

1. Switch 节点(3+ cases + default)正确路由
2. 不匹配 → 走 default
3. 多层嵌套 chain ⊃ parallel ⊃ chain 真实链路 COMPLETED
4. 嵌套 workflow 端到端测试 5+ 场景全绿
5. workflow.* 指标正常落 PG metrics_samples
6. verify.sh 仍全绿(228+5 测试 + 新加 5+)

## 11. 交付清单

- WorkflowStep sealed 加 Switch
- SwitchDef / CaseDef domain record
- WorkflowParseService 支持 switch 节点解析
- SwitchExecutor 调度 + trace span
- WorkflowOrchestratorImpl dispatch 扩展
- 5+ 嵌套端到端测试
- 完整 verify.sh 仍绿

## 12. 不在本期

- C4 嵌套 DAG(workflow 节点内嵌 parallel/switch)
- workflow 事件 Webhook(下轮)
- WorkflowRun 时间轴 UI(下轮)
