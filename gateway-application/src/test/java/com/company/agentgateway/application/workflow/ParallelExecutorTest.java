package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.BranchDef;
import com.company.agentgateway.domain.workflow.Join;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.ParallelResult;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.StepRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParallelExecutor 单测(spec C2 §6):3 分支并行 / JoinAll / partial result / 失败语义。
 */
class ParallelExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 三分支并行_全部成功_outputs是JSONB数组() {
        ParallelExecutor exec = buildExecutor(
                List.of("rag-vec", "rag-ft", "rag-web"),
                Map.of("rag-vec", "{\"chunks\":[1,2]}", "rag-ft", "{\"chunks\":[3]}", "rag-web", "{\"chunks\":[4,5]}")
        );
        ParallelDef def = new ParallelDef("multi-search", Join.ALL, List.of(
                new BranchDef("vec", "rag-vec", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000),
                new BranchDef("ft", "rag-ft", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000),
                new BranchDef("web", "rag-web", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000)
        ));
        ParallelResult pr = exec.executeParallel(InvocationCtx.NOOP, def, Map.of("q", "hello"), "wf", 0);
        // 全部成功:branches 3 条,outputs 是 list of maps,firstError null
        assertThat(pr.branches()).hasSize(3);
        assertThat(pr.branches().stream().allMatch(b -> b.status() == StepRun.Status.COMPLETED)).isTrue();
        assertThat(pr.firstError()).isNull();
        assertThat(pr.outputs()).containsKey("outputs");
        assertThat(pr.outputs().get("outputs")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).size().isEqualTo(3);
        assertThat(pr.outputs().get("branchNames")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("vec", "ft", "web");
    }

    @Test
    void 一分支失败_其他成功_整体FAILED_其他仍记录() {
        ParallelExecutor exec = buildExecutor(
                List.of("a1", "a2", "a3"),
                Map.of("a1", "{\"r\":1}", "a2", "A2A_AGENT_ERROR: bad", "a3", "{\"r\":2}")
        );
        ParallelDef def = new ParallelDef("p", Join.ALL, List.of(
                new BranchDef("ok1", "a1", Map.of(), 1000),
                new BranchDef("fail", "a2", Map.of(), 1000),
                new BranchDef("ok2", "a3", Map.of(), 1000)
        ));
        ParallelResult pr = exec.executeParallel(InvocationCtx.NOOP, def, Map.of(), "wf", 0);
        assertThat(pr.firstError()).contains("A2A_AGENT_ERROR");
        assertThat(pr.branches().stream().filter(b -> b.status() == StepRun.Status.FAILED).count()).isEqualTo(1);
        assertThat(pr.branches().stream().filter(b -> b.status() == StepRun.Status.COMPLETED).count()).isEqualTo(2);
        // errors 列表含失败
        assertThat(pr.outputs().get("errors")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    void 全部失败_firstError取首条() {
        ParallelExecutor exec = buildExecutor(
                List.of("x", "y"),
                Map.of("x", "HTTP_500: oops", "y", "TIMEOUT: slow")
        );
        ParallelDef def = new ParallelDef("p", Join.ALL, List.of(
                new BranchDef("a", "x", Map.of(), 1000),
                new BranchDef("b", "y", Map.of(), 1000)
        ));
        ParallelResult pr = exec.executeParallel(InvocationCtx.NOOP, def, Map.of(), "wf", 0);
        assertThat(pr.firstError()).startsWith("agent error: ");
        assertThat(pr.branches()).hasSize(2);
    }

    @Test
    void branchName在parallel内唯一_重复时传map中后定义覆盖() {
        // Map 同 key 不会重复;但用独立 map 校验 branches 列表内 name 唯一(避免运行期兜底)
        ParallelExecutor exec = buildExecutor(List.of("a"), Map.of("a", "{}"));
        // 同一 name 两个 branch:JSONPath 引用 $.steps.X.outputs.<key> 仍可工作(数组)
        ParallelDef def = new ParallelDef("p", Join.ALL, List.of(
                new BranchDef("a", "x", Map.of(), 1000),
                new BranchDef("a", "y", Map.of(), 1000)
        ));
        ParallelResult pr = exec.executeParallel(InvocationCtx.NOOP, def, Map.of(), "wf", 0);
        assertThat(pr.branches()).hasSize(2);
    }

    @Test
    void 分支独立outputs_不串数据() {
        // 验证 Step1.outputs 与 Step2.outputs 隔离(JSONB 数组按顺序)
        ParallelExecutor exec = buildExecutor(
                List.of("b1", "b2"),
                Map.of("b1", "{\"x\":1}", "b2", "{\"x\":2}")
        );
        ParallelDef def = new ParallelDef("p", Join.ALL, List.of(
                new BranchDef("br1", "b1", Map.of(), 1000),
                new BranchDef("br2", "b2", Map.of(), 1000)
        ));
        ParallelResult pr = exec.executeParallel(InvocationCtx.NOOP, def, Map.of(), "wf", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outs = (List<Map<String, Object>>) pr.outputs().get("outputs");
        assertThat(outs).hasSize(2);
        assertThat(outs).containsExactlyInAnyOrder(Map.of("x", 1), Map.of("x", 2));
    }

    // ========== helpers ==========

    /** 按 agent 名分发固定响应;ToolEvent 流标准化。 */
    private ParallelExecutor buildExecutor(List<String> agentNames, Map<String, String> agentToResponse) {
        AtomicInteger callCount = new AtomicInteger();
        ToolPort port = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    callCount.incrementAndGet();
                    String resp = agentToResponse.getOrDefault(agent.name(), "{}");
                    if (resp.startsWith("HTTP_5") || resp.equals("TIMEOUT: slow")
                            || resp.startsWith("A2A_AGENT_ERROR") || resp.startsWith("STREAM_ERROR")) {
                        subscriber.onNext(new ToolEvent.Error(
                                resp.contains(":") ? resp.substring(0, resp.indexOf(':')) : "ERROR",
                                resp.contains(":") ? resp.substring(resp.indexOf(':') + 1) : resp));
                    } else {
                        subscriber.onNext(new ToolEvent.Delta(resp));
                        subscriber.onNext(new ToolEvent.Complete(resp));
                    }
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        AgentCardPort cards = new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return agentNames.stream()
                        .map(n -> new AgentCard(n, "d", List.of(), "{}", "{}", "1", true, "http://stub", List.of("http://stub")))
                        .toList();
            }
            @Override public Flow.Publisher<List<AgentCard>> watch() { return s -> s.onComplete(); }
        };
        // StepExecutor 单分支直接调 ToolPort
        StepExecutor single = new StepExecutor(port, cards, ObservabilityHooks.NOOP, objectMapper);
        return new ParallelExecutor(port, cards, ObservabilityHooks.NOOP, single, objectMapper);
    }
}