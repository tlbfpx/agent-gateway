package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.JsonPathResolver;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.application.workflow.repo.InMemoryWorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowOrchestratorImpl 端到端单测(spec C1 §5):
 * - 顺序执行:Step1 → Step2
 * - JSONPath 跨 step 引用:$.steps.<name>.outputs.<key>
 * - 失败立即终止 → WorkflowRun.FAILED
 * - Agent 未注册 → 抛 WorkflowRuntimeException
 */
class WorkflowOrchestratorImplTest {

    private final InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
    private final JsonPathResolver resolver = new JaywayJsonPathResolverAdapter(new ObjectMapper());
    private final AtomicInteger callCount = new AtomicInteger();

    private AgentCardPort stubAgentCardPort;
    private WorkflowOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        callCount.set(0);
        stubAgentCardPort = mockAgentCardPort("rag-agent", "summarizer-agent");
    }

    private ToolPort newStubToolPort() {
        ToolPort port = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    int n2 = callCount.incrementAndGet();
                    String response;
                    if (agent.name().equals("rag-agent")) {
                        response = "{\"chunks\":[\"chunk-1\",\"chunk-2\"]}";
                    } else if (agent.name().equals("summarizer-agent")) {
                        response = "{\"summary\":\"hello world\"}";
                    } else {
                        response = "{\"text\":\"raw\"}";
                    }
                    subscriber.onNext(new ToolEvent.Delta(response));
                    subscriber.onNext(new ToolEvent.Complete(response));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        return port;
    }

    private WorkflowOrchestratorImpl newOrchestrator() {
        return new WorkflowOrchestratorImpl(
                newStubToolPort(), stubAgentCardPort, ObservabilityHooks.NOOP,
                repository, resolver, new ObjectMapper(), null);
    }

    @Test
    void chain顺序执行且JSONPath引用上一步outputs() {
        // 显式给 Step2 一个 inputs(测试编排顺序与仓库持久化;JSONPath 跨步引用细节测试见独立用例)
        WorkflowDef def = new WorkflowDef(
                "rag-summary",
                List.of(
                        new WorkflowStep.Single(new StepDef("retrieve", "rag-agent",
                                Map.of("query", new JsonPathExpression("$.inputs.question")),
                                null)),
                        new WorkflowStep.Single(new StepDef("summarize", "summarizer-agent",
                                Map.of("context", new JsonPathExpression("$.inputs.question")),
                                null))
                ),
                Map.of()
        );
        WorkflowOrchestratorImpl orc = newOrchestrator();
        WorkflowRun run = orc.run(def, Map.of("question", "hi"), InvocationCtx.NOOP);
        if (run.status() == WorkflowRun.Status.FAILED) {
            throw new AssertionError("Chain failed: " + run.steps().get(0).errorMessage());
        }
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(run.steps()).hasSize(2);
        // Step1 outputs
        assertThat(run.steps().get(0).outputs()).containsEntry("chunks", List.of("chunk-1", "chunk-2"));
        // Step2 outputs(独立 Agent)
        assertThat(run.steps().get(1).outputs()).containsEntry("summary", "hello world");
        // 调用了 2 次 ToolPort
        assertThat(callCount.get()).isEqualTo(2);
        // repository 保存
        assertThat(repository.find(run.runId())).isPresent();
    }

    @Test
    void JSONPath引用上一步outputs跨步() {
        WorkflowDef def = new WorkflowDef(
                "cross-step",
                List.of(
                        new WorkflowStep.Single(new StepDef("retrieve", "rag-agent",
                                Map.of("query", new JsonPathExpression("$.inputs.q")), null)),
                        new WorkflowStep.Single(new StepDef("summarize", "summarizer-agent",
                                Map.of("context", new JsonPathExpression("$.steps.retrieve.outputs.chunks")), null))
                ),
                Map.of()
        );
        WorkflowOrchestratorImpl orc = newOrchestrator();
        WorkflowRun run = orc.run(def, Map.of("q", "hello"), InvocationCtx.NOOP);
        // 跨步引用 + 单 step outputs 验证
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(run.steps().get(0).outputs()).containsKey("chunks");
        assertThat(run.steps().get(1).outputs()).containsEntry("summary", "hello world");
    }

    @Test
    void 失败立即终止_后续step不执行() {
        WorkflowDef def = new WorkflowDef("failing", List.of(
                new WorkflowStep.Single(new StepDef("step1", "rag-agent", Map.of(), null)),
                new WorkflowStep.Single(new StepDef("step2", "summarizer-agent", Map.of(), null))
        ), Map.of());
        // 用一个一定失败的 ToolPort
        ToolPort failingPort = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    subscriber.onNext(new ToolEvent.Error("A2A_AGENT_ERROR", "agent boom"));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        WorkflowOrchestratorImpl failingOrch = new WorkflowOrchestratorImpl(
                failingPort, stubAgentCardPort, ObservabilityHooks.NOOP,
                repository, resolver, new ObjectMapper(), null);
        WorkflowRun run = failingOrch.run(def, Map.of(), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.FAILED);
        // step1 失败已记录;step2 未执行
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).status()).isEqualTo(StepRun_Status_FAILED());
        assertThat(run.steps().get(0).errorMessage()).contains("agent boom");
    }

    @Test
    void agent未注册导致workflow失败() {
        WorkflowDef def = new WorkflowDef("x", List.of(
                new WorkflowStep.Single(new StepDef("s1", "ghost-agent", Map.of(), null))
        ), Map.of());
        WorkflowRun run = newOrchestrator().run(def, Map.of(), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.FAILED);
        assertThat(run.steps().get(0).errorMessage()).contains("agent not registered");
    }

    @Test
    void JSONPath引用不存在的字段导致workflow失败() {
        WorkflowDef def = new WorkflowDef("x", List.of(
                new WorkflowStep.Single(new StepDef("s1", "rag-agent",
                        Map.of("missing", new JsonPathExpression("$.steps.nope.outputs.x")),
                        null))
        ), Map.of());
        WorkflowRun run = newOrchestrator().run(def, Map.of(), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.FAILED);
        assertThat(run.steps().get(0).errorMessage()).contains("JsonPath context not satisfied");
    }

    private static AgentCardPort mockAgentCardPort(String... names) {
        return new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return java.util.Arrays.stream(names)
                        .map(n -> new AgentCard(n, "test", List.of(), "{}", "{}", "1", true,
                                "http://stub/" + n, List.of("http://stub/" + n)))
                        .toList();
            }
            @Override public Flow.Publisher<List<AgentCard>> watch() {
                return subscriber -> subscriber.onComplete();
            }
        };
    }

    private static com.company.agentgateway.domain.workflow.StepRun.Status StepRun_Status_FAILED() {
        return com.company.agentgateway.domain.workflow.StepRun.Status.FAILED;
    }
}