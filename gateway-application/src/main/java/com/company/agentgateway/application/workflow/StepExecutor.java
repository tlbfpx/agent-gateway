package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.BranchDef;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.ParallelResult;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.SwitchDef;
import com.company.agentgateway.domain.workflow.SwitchResult;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.company.agentgateway.domain.workflow.WorkflowStepExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单步执行器(spec C1 §5 + C2 单分支):同步调用 ToolPort,收集 ToolEvent 流,解析输出为 Map。
 *
 * <p>失败语义:
 * <ul>
 *   <li>ToolEvent.Error → WorkflowRuntimeException(整个 workflow 失败)</li>
 *   <li>调用超时(timeoutMs) → WorkflowRuntimeException</li>
 *   <li>输出非 JSON → 降级为 outputs={"text": raw}</li>
 * </ul>
 *
 * <p>实现 WorkflowStepExecutor 端口:C2 Parallel 通过 executeParallel 调度多分支并行,
 * 每个分支通过 {@link #executeSingle} 单步执行(本实现)。ParallelExecutor 复用单步执行。
 */
public class StepExecutor implements WorkflowStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolPort toolPort;
    private final AgentCardPort agentCardPort;
    private final ObservabilityHooks observabilityHooks;
    private final ObjectMapper objectMapper;

    public StepExecutor(ToolPort toolPort, AgentCardPort agentCardPort,
                        ObservabilityHooks observabilityHooks,
                        ObjectMapper objectMapper) {
        this.toolPort = toolPort;
        this.agentCardPort = agentCardPort;
        this.observabilityHooks = observabilityHooks;
        this.objectMapper = objectMapper;
    }

    /** C1 路径:执行单步(返回 inputs 快照 + outputs + durationMs)。失败 → 抛 WorkflowRuntimeException。 */
    @Override
    public StepRun executeSingle(InvocationCtx ctx, StepDef stepDef,
                                  Map<String, Object> inputs, String workflowName, int stepIndex) {
        return execute(workflowName, stepDef, stepIndex, inputs, ctx);
    }

    /** C2 路径:并行节点(Flux.merge 调度各 branch;JoinAll 严格;partial result)。 */
    @Override
    public ParallelResult executeParallel(InvocationCtx ctx, ParallelDef parallel,
                                          Map<String, Object> inputs, String workflowName, int stepIndex) {
        long start = System.currentTimeMillis();
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicInteger idx = new AtomicInteger(0);
        List<Mono<StepRun>> monos = new ArrayList<>();
        for (BranchDef branch : parallel.branches()) {
            int branchIdx = idx.getAndIncrement();
            Map<String, Object> branchInputs = inputs;
            monos.add(Mono.fromCallable(() -> {
                long bStart = System.currentTimeMillis();
                try {
                    StepDef stepDef = new StepDef(branch.name(), branch.agent(),
                            branch.inputs(), branch.timeoutMs());
                    return executeSingle(ctx, stepDef, branchInputs, workflowName, stepIndex);
                } finally {
                    observabilityHooks.onAgentComplete("workflow", "parallel." + branch.name(),
                            System.currentTimeMillis() - bStart, false);
                }
            }).subscribeOn(Schedulers.parallel())
              .onErrorResume(e -> {
                  failureCount.incrementAndGet();
                  return Mono.just(new StepRun(branch.name(), StepRun.Status.FAILED,
                          Map.of(), Map.of(), 0L,
                          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                          null, branchIdx, branch.name()));
              }));
        }
        List<StepRun> branches = Flux.merge(monos).collectList().block(Duration.ofMinutes(2));
        if (branches == null) {
            throw new WorkflowRuntimeException(workflowName, parallel.name(), stepIndex, "parallel timeout");
        }
        List<Map<String, Object>> outputsList = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (StepRun b : branches) {
            outputsList.add(b.outputs() == null ? Map.of() : b.outputs());
            if (b.status() == StepRun.Status.FAILED && b.errorMessage() != null) errors.add(b.errorMessage());
        }
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("outputs", outputsList);
        outputs.put("errors", errors);
        outputs.put("branchNames", branches.stream().map(StepRun::name).toList());
        String firstError = errors.isEmpty() ? null : errors.get(0);
        observabilityHooks.onChatComplete("workflow", "parallel." + parallel.name(),
                System.currentTimeMillis() - start, firstError == null);
        log.debug("parallel {}.{} {} branches, {} failures",
                new Object[]{workflowName, parallel.name(), branches.size(), failureCount.get()});
        return new ParallelResult(branches, outputs, System.currentTimeMillis() - start, firstError);
    }

    /** C1 老路径(OrchestratorImpl 仍可能调用)。 */
    public StepRun execute(String workflowName, StepDef stepDef, int stepIndex,
                          Map<String, Object> inputs, InvocationCtx ctx) {
        long start = System.currentTimeMillis();
        AgentCard card = findAgent(workflowName, stepDef, stepIndex);
        observabilityHooks.onAgentInvoke("workflow", card.name(), "workflow-" + workflowName);

        String argsJson = serializeArgs(card.name(), inputs);
        AtomicReference<String> resultText = new AtomicReference<>("");
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        toolPort.invoke(card, argsJson, ctx).subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ToolEvent item) {
                if (item instanceof ToolEvent.Delta d) {
                    resultText.updateAndGet(prev -> prev + d.content());
                } else if (item instanceof ToolEvent.Complete c) {
                    // 若 Delta 为空,采用 Complete.fullResult
                    if (resultText.get().isBlank() && c.fullResult() != null && !c.fullResult().isBlank()) {
                        resultText.set(c.fullResult());
                    }
                } else if (item instanceof ToolEvent.Error e) {
                    errorRef.set(e.code() + ": " + e.message());
                    done.countDown();
                }
            }
            @Override public void onError(Throwable throwable) {
                errorRef.set("STREAM_ERROR: " + throwable.getMessage());
                done.countDown();
            }
            @Override public void onComplete() { done.countDown(); }
        });

        long timeout = stepDef.timeoutMs() == null ? 30000L : stepDef.timeoutMs();
        try {
            if (!done.await(timeout, TimeUnit.MILLISECONDS)) {
                throw new WorkflowRuntimeException(workflowName, stepDef.name(), stepIndex,
                        "step timed out after " + timeout + "ms");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new WorkflowRuntimeException(workflowName, stepDef.name(), stepIndex,
                    "step interrupted", ie);
        }

        long duration = System.currentTimeMillis() - start;

        if (errorRef.get() != null) {
            throw new WorkflowRuntimeException(workflowName, stepDef.name(), stepIndex,
                    "agent error: " + errorRef.get());
        }

        Map<String, Object> outputs = parseOutput(resultText.get());
        observabilityHooks.onChatComplete("workflow", card.name(), duration, true);
        return new StepRun(stepDef.name(), StepRun.Status.COMPLETED, inputs, outputs, duration, null);
    }

    private AgentCard findAgent(String workflowName, StepDef stepDef, int stepIndex) {
        return agentCardPort.snapshot().stream()
                .filter(a -> a.name().equals(stepDef.agent()))
                .findFirst()
                .orElseThrow(() -> new WorkflowRuntimeException(workflowName, stepDef.name(), stepIndex,
                        "agent not registered: " + stepDef.agent()));
    }

    private String serializeArgs(String agentName, Map<String, Object> inputs) {
        try {
            // 包一层 {"args": inputs} 让 Agent 端按命名空间取
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("args", inputs);
            return objectMapper.writeValueAsString(wrapped);
        } catch (Exception e) {
            throw new WorkflowRuntimeException("?", agentName, -1,
                    "serialize args failed: " + e.getMessage());
        }
    }

    /** 解析 Agent 输出为 Map;失败时降级为 {"text": raw}。 */
    private Map<String, Object> parseOutput(String raw) {
        if (raw == null || raw.isBlank()) return Map.of("text", "");
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            // 非 JSON 输出:降级(让后续 JSONPath 引用失败时清晰)
            return Map.of("text", raw);
        }
    }

    // 消除 Duration import unused 警告
    @SuppressWarnings("unused")
    private static final Duration UNUSED = Duration.ZERO;

    /** C3:Switch 节点执行 — 选 case 由 OrchestratorImpl.dispatch 完成;本方法只跑选中的 StepDef。 */
    @Override
    public SwitchResult executeSwitch(InvocationCtx ctx, SwitchDef switchDef,
                                       Map<String, Object> inputs, String workflowName, int stepIndex) {
        // C3 简化:实际 case 匹配由 OrchestratorImpl 完成,本方法作为单步跑选中 step 的接口
        // (inputs 已经是 chosenStep 解析后的值)
        // 这里取 defaultStep 是接口占位 — OrchestratorImpl 实际不会调这个方法(直接调 executeSingle),
        // 但需要满足接口契约
        com.company.agentgateway.domain.workflow.WorkflowStep chosen = switchDef.defaultStep();
        if (chosen instanceof com.company.agentgateway.domain.workflow.WorkflowStep.Single s) {
            StepRun run = executeSingle(ctx, s.def(), inputs, workflowName, stepIndex);
            return new SwitchResult(run, "default", List.of(), run.outputs(), null);
        }
        throw new com.company.agentgateway.domain.workflow.WorkflowRuntimeException(
                workflowName, switchDef.name(), stepIndex,
                "default 含 Parallel/Switch 嵌套应走 OrchestratorImpl.dispatch");
    }
}
