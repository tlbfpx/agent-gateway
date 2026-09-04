# C2 并行 Workflow 立项提案(待用户决策)

- **日期:** 2026-08-20
- **目的:** C1 Chain 完成后,立项 C2 — 多源 fan-out 工作流
- **状态:** 提案(待用户决策)

## 1. 为什么需要 C2

C1 Chain 是线性流水线(RAG → 总结 → 翻译)。**多源检索/多模型 fan-out** 是更常见的业务场景:

```
                  ┌─ Agent A (向量 DB) ──┐
User Query ──→   ├─ Agent B (全文索引) ──┼─→ Agent C (合并/重排) ──→ Response
                  └─ Agent N (外部 API) ──┘
```

C2 提供:
- **性能**:多源并行(3 个 Agent 同时跑,延迟从 3x → 1x)
- **鲁棒性**:部分失败可降级(JoinAny 模式:任一源成功即可)
- **灵活性**:每个分支独立配置超时/重试/重试策略

## 2. 设计草案

### 2.1 DSL 扩展

在 WorkflowDef.steps[] 基础上,新增 `parallel` 节点:

```yaml
name: multi-source-rag
steps:
  - name: parallel-search
    parallel:
      join: all   # all | any | majority(2/3)
      branches:
        - { name: vector, agent: rag-vector, inputs: { q: "$.inputs.q" } }
        - { name: fulltext, agent: rag-fulltext, inputs: { q: "$.inputs.q" } }
        - { name: web, agent: rag-web, inputs: { q: "$.inputs.q" }, timeoutMs: 5000 }
  - name: re-rank
    agent: rerank-agent
    inputs:
      results: "$.steps.parallel-search.outputs[*]"
```

### 2.2 与 C1 关系

- **继承**:C2 在 C1 之上,C1 的 Chain/JSONPath 全部继续工作
- **新增**:parallel 节点类型(域为 steps 的子集);join 模式决定结果合并
- **嵌套**:parallel 可嵌套 Chain(branch 是 chain);Chain 可包含 parallel

### 2.3 关键设计点

| 问题 | 决策方向 |
|---|---|
| 并行调度 | Reactor `Mono.zip` 全部结果 / `Flux.first` 取最快成功 / `Flux.merge` 全部返回(失败也累加) |
| 部分失败 | JoinAny 容忍失败(部分失败 → 整体成功);JoinAll 全部成功;JoinMajority 超半数 |
| 资源占用 | 并行分支数 = Reactor 并发度;B 阶段熔断已就绪 → 某分支多次失败自动熔断 |
| 错误归一化 | 任一分支 fail-fast 时 JoinAll → 整体 FAILED;partial result 持久化(便于回放) |
| 持久化 | StepRun 加 `parentStepIndex` 字段标识并行组内兄弟节点 |
| 指标 | `workflow.parallel.branches`(分支数)+ `workflow.parallel.failures`(失败数) |

## 3. 实现路线(分阶段交付)

| 阶段 | 范围 | 工作量 |
|---|---|---|
| **C2.1** | 基础并行 + JoinAll + 串接 StepRun parent 字段 | ★★ |
| C2.2 | JoinAny / JoinMajority + 部分失败语义 | ★★ |
| C2.3 | parallel 嵌套 Chain | ★★★ |
| C2.4 | 失败重试 per-branch + 超时单独配置 | ★★ |

## 4. 验收标准(初稿)

1. 三分支并行 chain,总耗时 ≈ max(单分支),不是 sum
2. JoinAll 任何一分支失败 → 整体 FAILED + partial 落库
3. JoinAny 至少一分支成功 → 整体 COMPLETED
4. trace spans:parallel 父 span + 各分支 child span(独立 traceId 子树)
5. 指标:workflow.parallel.branches 计数 + workflow.parallel.failures 触发告警
6. verify.sh 全绿 + E2E 真实链路 + 失败转移

## 5. 风险

- **嵌套复杂度**:parallel 嵌套 Chain 让 WorkflowRun 树状化(StepRun 加 `children[]`);持久化 schema 调整
- **重试爆炸**:每分支独立重试 → 极端情况下触发熔断已就绪(B 阶段)
- **JSONPath 跨步**:`.outputs[*]` 通配符(取所有分支输出数组)Jayway 需 support;jayway 2.9 支持

## 6. 用户决策点

| 决策 | 选项 |
|---|---|
| **范围优先级** | (a) C2.1 基础并行(最常用)  (b) C2.1+2.2 一次到位 |
| **Join 默认** | (a) JoinAll 严格  (b) JoinAny 容错优先 |
| **嵌套** | (a) C2.3 不做(平行 only)  (b) 全做(C2.1+2.3) |
| **是否走新 spec** | (a) 是(独立 brainstorming)  (b) 复用 C1 spec 增量 |

## 7. 与其他工作的依赖

- C2 可独立立项,**不依赖 C1 改进**;C1 留有余地(steps 数组、单 JSONPath 引用、单 metric)
- 与 A/B 的复用:
  - A 的 trace 体系天然支持 parallel 子 span
  - B 的 Resilience4j 熔断自动覆盖每分支(per agentName)
  - 不需要新增 Resilience4j 配置

## 8. 建议

**推荐 C2.1 + C2.3 + JoinAll(先严格后灵活)**:
- 价值最高的多源 fan-out 场景落地
- 与 C1 同构(WorkflowRun.steps[] 加 parent 字段)
- 失败语义清晰(任意失败 → 整体失败 → 与现有告警兼容)

约 1-2 天实现,1 天测试与 E2E。

---

**等你确认**:选哪个范围(决策点 1)+ Join 默认(决策点 2),我直接进入 brainstorming 流程做正式 spec。
