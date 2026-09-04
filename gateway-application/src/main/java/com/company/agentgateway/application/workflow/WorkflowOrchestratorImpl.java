package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.JsonPathResolver;
import com.company.agentgateway.domain.workflow.ParallelResult;
import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowOrchestrator;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.domain.workflow.WorkflowStep;

import java.util.Objects;
import com.company.agentgateway.domain.workflow.WorkflowStepExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WorkflowOrchestrator 实现(spec C1 §5):
 * 同步遍历 Step → 解析 inputs → 执行 → 收集输出 → 失败 fail-fast。
 *
 * <p>trace 接入:每步通过 ObservabilityHooks.onAgentInvoke/Complete 落点,与现有 ChatOrchestrator
 * 一致;埋点由 C 阶段 3(AdminWorkflowController + GatewayTracer)负责 root span。
 */
public class WorkflowOrchestratorImpl implements WorkflowOrchestrator {

    /** C4:switch 嵌套深度上限(顶层 = 1)。 */
    private static final int MAX_NESTING_DEPTH = 5;

    private final ToolPort toolPort;
    private final AgentCardPort agentCardPort;
    private final ObservabilityHooks observabilityHooks;
    private final WorkflowRepository repository;
    private final JsonPathResolver jsonPathResolver;
    private final ObjectMapper objectMapper;
    /** A 阶段 webhook 通道(可能为 null,工作流 spec 不强制依赖)— 复用现有 GatewayEvents 端口 */
    private final com.company.agentgateway.domain.observability.GatewayEvents gatewayEvents;
    /** C4:dispatch 用执行器(run 时惰性初始化,避免构造期依赖顺序) */
    private final StepExecutor singleExecutor;
    private final ParallelExecutor parallelExecutor;

    public WorkflowOrchestratorImpl(ToolPort toolPort, AgentCardPort agentCardPort,
                                    ObservabilityHooks observabilityHooks,
                                    WorkflowRepository repository,
                                    JsonPathResolver jsonPathResolver,
                                    ObjectMapper objectMapper,
                                    com.company.agentgateway.domain.observability.GatewayEvents gatewayEvents) {
        this.toolPort = toolPort;
        this.agentCardPort = agentCardPort;
        this.observabilityHooks = observabilityHooks;
        this.repository = repository;
        this.jsonPathResolver = jsonPathResolver;
        this.objectMapper = objectMapper;
        this.gatewayEvents = gatewayEvents;
        this.singleExecutor = new StepExecutor(toolPort, agentCardPort, observabilityHooks, objectMapper);
        this.parallelExecutor = new ParallelExecutor(
                toolPort, agentCardPort, observabilityHooks, this.singleExecutor, objectMapper);
    }

    @Override
    public WorkflowRun run(WorkflowDef def, Map<String, Object> inputs, InvocationCtx ctx) {
        // 初始化运行时上下文:inputs + steps 占位(全部可变 Map,后续 JSONPath 解析 + 子 map.put)
        Map<String, Object> runtimeContext = new HashMap<>();
        Map<String, Object> mergedInputs = new HashMap<>(def.defaultInputs());
        if (inputs != null) mergedInputs.putAll(inputs);
        runtimeContext.put("inputs", mergedInputs);
        Map<String, Object> stepCtx = new HashMap<>();
        runtimeContext.put("steps", stepCtx);

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        List<StepRun> steps = new ArrayList<>();
        WorkflowRun run = new WorkflowRun(runId, def.name(), WorkflowRun.Status.RUNNING,
                startedAt, null, new HashMap<>(), steps);

        try {
            for (int i = 0; i < def.steps().size();i++) {
                executeStep(def.steps().get(i), i, 1, null, def, runtimeContext, stepCtx, steps, ctx);
            }
            return finalizeRun(run, WorkflowRun.Status.COMPLETED, steps);
        } catch (WorkflowRuntimeException e) {
            // 失败终止:记录失败的 step(含 errorMessage),已成功的 steps 保留
            recordFailedStep(steps, e.stepName(), e.stepIndex(), e.getMessage());
            return finalizeRun(run, WorkflowRun.Status.FAILED, steps);
        } catch (RuntimeException e) {
            // 未知异常:记录到当前位置(若可定位)
            recordFailedStep(steps, "?", steps.size(), e.getMessage());
            return finalizeRun(run, WorkflowRun.Status.FAILED, steps);
        }
    }

    /**
     * C4 递归 dispatch:执行一个 WorkflowStep 节点(Single / Parallel / Switch)。
     * depth 是嵌套深度(顶层 = 1,每层 switch case/default 下钻 +1),上限 5 防无限递归。
     * parentTag 是嵌套链路标签(如 "sw>case-a>sw2>default",顶层 null),写入 StepRun.branchName
     * 供 UI 按 switch 分组渲染嵌套结构(#69)。
     * switch 节点命中后输出写到 switch 自己的 name 下(与 C3 语义一致,后续 $.steps.<sw>.outputs 引用)。
     */
    private void executeStep(WorkflowStep step, int i, int depth, String parentTag, WorkflowDef def,
                             Map<String, Object> runtimeContext, Map<String, Object> stepCtx,
                             List<StepRun> steps, InvocationCtx ctx) {
        if (step instanceof WorkflowStep.Single s) {
            Map<String, Object> stepInputs = resolveInputs(s.def().inputs(), runtimeContext);
            StepRun stepRun = singleExecutor.executeSingle(ctx, s.def(), stepInputs, def.name(), i);
            if (parentTag != null) {
                stepRun = withTag(stepRun, i, parentTag);
            }
            steps.add(stepRun);
            writeStepOutputs(stepCtx, s.def().name(), stepRun.outputs());
        } else if (step instanceof WorkflowStep.Parallel p) {
            // 与 Single 相同:以第一分支的 inputs 模板解析(parallel 各分支共享同一运行时上下文)
            Map<String, Object> stepInputs = resolveInputs(
                    p.def().branches().isEmpty() ? Map.of() : p.def().branches().get(0).inputs(), runtimeContext);
            ParallelResult pr = parallelExecutor.executeParallel(ctx, p.def(), stepInputs, def.name(), i);
            // 写入所有 branches 作为独立 StepRun(可观测 / 持久化);
            // 嵌套 parallel(case.step=Parallel)的分支补 parentIndex + 链路 tag
            for (int bi = 0; bi < pr.branches().size(); bi++) {
                StepRun b = pr.branches().get(bi);
                if (b.parentIndex() == null || b.parentIndex() < 0) {
                    b = new StepRun(b.name(), b.status(), b.inputs(), b.outputs(), b.durationMs(),
                            b.errorMessage(), i, bi, b.branchName());
                }
                if (parentTag != null) {
                    b = withTag(b, i, parentTag + ">" + b.name());
                }
                steps.add(b);
            }
            // parallel 节点整体作为伪 StepRun 写入(便于持久化 + JSONPath 引用 outputs 数组)
            writeStepOutputs(stepCtx, p.def().name(), pr.outputs());
            if (pr.firstError() != null) {
                throw new WorkflowRuntimeException(def.name(), p.def().name(), i, pr.firstError());
            }
        } else if (step instanceof WorkflowStep.Switch sw) {
            if (depth > MAX_NESTING_DEPTH) {
                throw new WorkflowRuntimeException(def.name(), sw.def().name(), i,
                        "switch 嵌套深度超过 " + MAX_NESTING_DEPTH);
            }
            Object keyVal = jsonPathResolver.resolve(sw.def().key().raw(), runtimeContext);
            WorkflowStep chosen = sw.def().defaultStep();
            String matched = "default";
            for (var c : sw.def().cases()) {
                if (Objects.equals(c.value(), keyVal)) {
                    chosen = c.step();
                    matched = "value=" + c.value();
                    break;
                }
            }
            String childTag = (parentTag == null ? "" : parentTag + ">") + sw.def().name() + ">case:" + matched;
            // 命中的子节点在 switch name 下求值:Single/Parallel 直接跑,
            // Switch 递归 dispatch(输出统一写到本层 switch name,保持 $.steps.<sw>.outputs 引用语义)
            int before = steps.size();
            executeStep(chosen, i, depth + (chosen instanceof WorkflowStep.Switch ? 1 : 0),
                    childTag, def, runtimeContext, stepCtx, steps, ctx);
            // 将子节点输出同时挂到 switch name 下,后续 JSONPath 可用 $.steps.<switch>.outputs...
            for (int k = before; k < steps.size(); k++) {
                StepRun r = steps.get(k);
                if (r.status() == StepRun.Status.COMPLETED) {
                    writeStepOutputs(stepCtx, sw.def().name(), r.outputs());
                }
            }
            observabilityHooks.onChatComplete("workflow", "switch." + sw.def().name(), 0L, true);
            System.out.println("[workflow] switch " + def.name() + "." + sw.def().name()
                    + " matched=" + matched + " depth=" + depth);
        } else {
            throw new WorkflowRuntimeException(def.name(), "?", i, "unknown step type: " + step);
        }
    }

    /** #69:给嵌套子 step 打标(parentIndex = 顶层 step 索引,branchName = 嵌套链路 tag)。 */
    private static StepRun withTag(StepRun r, int parentIndex, String tag) {
        return new StepRun(r.name(), r.status(), r.inputs(), r.outputs(), r.durationMs(),
                r.errorMessage(), parentIndex, r.branchIndex(), tag);
    }

    /** 解析 inputs:每条 JSONPath / 字面量在 runtimeContext 上求值。 */
    private Map<String, Object> resolveInputs(Map<String, JsonPathExpression> template,
                                              Map<String, Object> runtimeContext) {
        Map<String, Object> out = new HashMap<>();
        if (template == null) return out;
        for (Map.Entry<String, JsonPathExpression> e : template.entrySet()) {
            out.put(e.getKey(), jsonPathResolver.resolve(e.getValue().raw(), runtimeContext));
        }
        return out;
    }

    private void writeStepOutputs(Map<String, Object> stepCtx, String name, Map<String, Object> outputs) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("outputs", outputs);
        stepCtx.put(name, entry);
    }

    private void recordFailedStep(List<StepRun> steps, String stepName, int stepIndex, String error) {
        steps.add(new StepRun(stepName, com.company.agentgateway.domain.workflow.StepRun.Status.FAILED,
                Map.of(), Map.of(), 0L, error));
    }

    private WorkflowRun finalizeRun(WorkflowRun run, WorkflowRun.Status status, List<StepRun> steps) {
        WorkflowRun finished = new WorkflowRun(run.runId(), run.workflowName(), status,
                run.startedAt(), Instant.now(), Map.of(), steps);
        long durationMs = Duration.between(run.startedAt(), Instant.now()).toMillis();
        // 显式 publish 工作流级 metric(白名单 workflow. 前缀),便于告警与趋势页:
        observabilityHooks.onWorkflowComplete(
                "workflow", run.workflowName(), run.runId(), durationMs,
                status == WorkflowRun.Status.COMPLETED);
        // 复用 A 阶段 webhook 通道: workflow.run.completed / workflow.run.failed 事件
        // (gatewayEvents 可空:无 webhook 配置时跳过,不影响主路径)
        if (gatewayEvents != null) {
            String type = status == WorkflowRun.Status.COMPLETED
                    ? "workflow.run.completed" : "workflow.run.failed";
            gatewayEvents.publish(type, java.util.Map.of(
                    "runId", run.runId(),
                    "status", status.name(),
                    "durationMs", durationMs,
                    "stepCount", steps.size()));
        }
        try {
            return repository.save(finished);
        } catch (RuntimeException e) {
            // 仓储失败不影响返回(已 finalize)
            return finished;
        }
    }
}