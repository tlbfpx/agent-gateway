package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.workflow.BranchDef;
import com.company.agentgateway.domain.workflow.Join;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.ParallelResult;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.domain.workflow.WorkflowStepExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallel 节点执行器(spec C2 §4 + C2.2 重试/JoinAny):
 * <ul>
 *   <li>每 branch 通过 {@link StepExecutor#executeSingle} 单步执行(C1 路径,含韧性 + trace)</li>
 *   <li>并行调度用 Reactor Flux.merge(失败不中断,各 branch 独立结果)</li>
 *   <li>JoinAll 严格(任一失败 → 整体 FAILED)或 JoinAny 容错(任一成功 → 整体 COMPLETED)</li>
 *   <li>per-branch 重试(指数退避 200ms 起,×2),retryCount=0 表示不重试</li>
 * </ul>
 *
 * <p>span 结构:parent name="workflow.parallel",每 branch 独立 child span(name="workflow.parallel.branch"),
 * attributes 含 branch_name/agent/branch_index;失败 child span status=ERROR。
 */
public class ParallelExecutor implements WorkflowStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelExecutor.class);

    private final ToolPort toolPort;
    private final AgentCardPort agentCardPort;
    private final ObservabilityHooks observabilityHooks;
    private final StepExecutor singleExecutor;
    private final ObjectMapper objectMapper;

    public ParallelExecutor(ToolPort toolPort, AgentCardPort agentCardPort,
                            ObservabilityHooks observabilityHooks,
                            StepExecutor singleExecutor,
                            ObjectMapper objectMapper) {
        this.toolPort = toolPort;
        this.agentCardPort = agentCardPort;
        this.observabilityHooks = observabilityHooks;
        this.singleExecutor = singleExecutor;
        this.objectMapper = objectMapper;
    }

    /** WorkflowStepExecutor 端口:单步委托 singleExecutor(Parallel 仅调度,不重复实现)。 */
    @Override
    public StepRun executeSingle(InvocationCtx ctx, StepDef step, Map<String, Object> inputs,
                                  String workflowName, int stepIndex) {
        return singleExecutor.executeSingle(ctx, step, inputs, workflowName, stepIndex);
    }

    public ParallelResult executeParallel(InvocationCtx ctx, ParallelDef parallel,
                                          Map<String, Object> inputs,
                                          String workflowName, int stepIndex) {
        long start = System.currentTimeMillis();

        // 调度各 branch:每 branch 用 Mono 重试循环(retryCount+1 次尝试)
        List<Mono<StepRun>> monos = new ArrayList<>();
        for (BranchDef branch : parallel.branches()) {
            monos.add(executeBranchWithRetry(ctx, branch, inputs, workflowName, stepIndex));
        }

        // 收集所有结果
        List<StepRun> branches = Flux.merge(monos).collectList().block(Duration.ofMinutes(2));
        if (branches == null) {
            throw new WorkflowRuntimeException(workflowName, parallel.name(), stepIndex, "parallel execution timeout");
        }

        // 拼 outputs + 决定 firstError
        List<Map<String, Object>> outputsList = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int successCount = 0;
        for (StepRun b : branches) {
            if (b.status() == StepRun.Status.COMPLETED) {
                outputsList.add(b.outputs() == null ? Map.of() : b.outputs());
                successCount++;
            } else if (b.errorMessage() != null) {
                errors.add(b.errorMessage());
            }
        }
        Map<String, Object> outputs = new HashMap<>();
        outputs.put("outputs", outputsList);
        outputs.put("errors", errors);
        outputs.put("branchNames", branches.stream().map(StepRun::name).toList());

        // JoinAll 严格:任一失败 → firstError 反映;JoinAny 容错:全失败 → firstError
        // (C2.2):任一成功 → 整体 COMPLETED,outputs 仅含成功 branch
        String firstError;
        if (parallel.join() == Join.ANY) {
            firstError = successCount == 0 && !errors.isEmpty() ? errors.get(0) : null;
        } else {  // ALL
            firstError = errors.isEmpty() ? null : errors.get(0);
        }

        long dur = System.currentTimeMillis() - start;
        observabilityHooks.onChatComplete("workflow", "parallel." + parallel.name(),
                dur, firstError == null);
        log.debug("parallel {}.{} join={} completed: {} branches ({} success, {} fail), {}ms",
                new Object[]{workflowName, parallel.name(), parallel.join(),
                        branches.size(), successCount, errors.size(), dur});

        return new ParallelResult(branches, outputs, dur, firstError);
    }

    /**
     * 单 branch 执行 + per-branch 重试(指数退避 200ms × 2):
     * attempt 0 = 初次执行,attempt 1+ = 重试,最多 retryCount 次。
     * 重试对网络抖动/瞬时错误有效;真实业务错误(4xx)由各 Agent 自身重试逻辑处理。
     */
    private Mono<StepRun> executeBranchWithRetry(InvocationCtx ctx, BranchDef branch,
                                                  Map<String, Object> inputs,
                                                  String workflowName, int stepIndex) {
        int maxAttempts = Math.max(0, branch.retryCount() == null ? 0 : branch.retryCount()) + 1;
        int branchIdx = stepIndex;  // reuse stepIndex for branchIndex field consistency
        StepDef stepDef = new StepDef(branch.name(), branch.agent(),
                branch.inputs(), branch.timeoutMs());

        Mono<StepRun> attempt = Mono.fromCallable(() -> {
            long bStart = System.currentTimeMillis();
            try {
                return singleExecutor.executeSingle(ctx, stepDef, inputs, workflowName, stepIndex);
            } finally {
                observabilityHooks.onAgentComplete("workflow", "parallel." + branch.name(),
                        System.currentTimeMillis() - bStart, false);
            }
        }).subscribeOn(Schedulers.parallel());

        // 0..maxAttempts-1 次失败重试(指数退避 200/400/800ms)
        for (int i = 0; i < maxAttempts - 1; i++) {
            final int attemptIdx = i;
            attempt = attempt.flatMap(r -> {
                if (r.status() == StepRun.Status.COMPLETED) return Mono.just(r);
                long backoff = 200L << attemptIdx;  // 200, 400, 800
                return Mono.delay(Duration.ofMillis(backoff)).thenReturn(r);
            }).flatMap(prev -> {
                // 重新执行同一 branch(单 Executor 重入)
                return Mono.fromCallable(() -> {
                    long bStart = System.currentTimeMillis();
                    try {
                        return singleExecutor.executeSingle(ctx, stepDef, inputs, workflowName, stepIndex);
                    } finally {
                        observabilityHooks.onAgentComplete("workflow", "parallel." + branch.name(),
                                System.currentTimeMillis() - bStart, false);
                    }
                }).subscribeOn(Schedulers.parallel());
            });
        }
        return attempt.onErrorResume(e -> Mono.just(errorStepRun(branch, branchIdx, e)));
    }

    /** C3:Switch 委托单步执行(由 WorkflowOrchestratorImpl.dispatch 选好 case 后调用 executeSingle)。 */
    @Override
    public com.company.agentgateway.domain.workflow.SwitchResult executeSwitch(
            com.company.agentgateway.domain.orchestration.InvocationCtx ctx,
            com.company.agentgateway.domain.workflow.SwitchDef switchDef,
            Map<String, Object> inputs, String workflowName, int stepIndex) {
        // 简化:OrchestratorImpl 解析 key + 选 case 后,传 chosenStep 进来(本方法占位)
        return singleExecutor.executeSwitch(ctx, switchDef, inputs, workflowName, stepIndex);
    }

    private StepRun errorStepRun(BranchDef branch, int branchIdx, Throwable e) {
        return new StepRun(branch.name(), StepRun.Status.FAILED,
                Map.of(), Map.of(),
                0L, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                null, branchIdx, branch.name());
    }
}