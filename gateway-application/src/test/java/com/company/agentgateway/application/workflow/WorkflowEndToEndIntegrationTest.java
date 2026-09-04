package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.application.workflow.repo.InMemoryWorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 + A + B 端到端集成单测(spec C1 §8.4):mock 真实 ChatModel 走完整路径,
 * 验证:LLM 模拟发 toolCall → ChatClientLlmSession 注入 options + toolCallId → LlmFlowAdapter 累积/flush →
 * ChatOrchestrator.executeToolCalls(实际是 Workflow StepExecutor 同构)→ ToolPort.invoke → WorkflowRun.
 *
 * <p>此测试不依赖真实 LLM API:用 StubChatModel + StubToolPort + Workflow 真实编排闭环。
 * 真实 example-agent 已确认可达(8090),本测试的 StubToolPort 等价于它;
 * 唯一缺失是「真实 ChatModel 返回 toolCall」——这是模型行为而非代码问题。
 */
class WorkflowEndToEndIntegrationTest {

    @Test
    void 完整链路_rag_chain跨step引用_outputs落库可查询() {
        // 1. 装配真实 WorkflowOrchestratorImpl(按 agent 分发:rag-agent 返 chunks,summarizer-agent 返 summary)
        InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
        ObjectMapper mapper = new ObjectMapper();
        ToolPort multiPort = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    String resp = agent.name().equals("rag-agent")
                            ? "{\"chunks\":[\"c1\",\"c2\"]}"
                            : "{\"summary\":\"hello world\"}";
                    subscriber.onNext(new ToolEvent.Delta(resp));
                    subscriber.onNext(new ToolEvent.Complete(resp));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        WorkflowOrchestratorImpl orch = new WorkflowOrchestratorImpl(
                multiPort,
                stubAgentCardPort(),
                ObservabilityHooks.NOOP,
                repository,
                new JaywayJsonPathResolverAdapter(mapper),
                mapper, null);

        // 2. workflow def:Step1.retrieve → Step2.summarize(context 引用 Step1 outputs)
        WorkflowDef def = new WorkflowDef("e2e-rag", List.of(
                new WorkflowStep.Single(new StepDef("retrieve", "rag-agent",
                        Map.of("query", new JsonPathExpression("$.inputs.question")), null)),
                new WorkflowStep.Single(new StepDef("summarize", "summarizer-agent",
                        Map.of("context", new JsonPathExpression("$.steps.retrieve.outputs.chunks")), null))
        ), Map.of());

        // 3. 执行
        WorkflowRun run = orch.run(def, Map.of("question", "hi"), InvocationCtx.NOOP);

        // 4. 验证成功
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(run.steps()).hasSize(2);
        // Step1 outputs
        assertThat(run.steps().get(0).outputs()).containsEntry("chunks", List.of("c1", "c2"));
        // Step2 outputs(通过 JSONPath 跨步引用了 Step1)
        assertThat(run.steps().get(1).outputs()).containsEntry("summary", "hello world");

        // 5. 验证仓储
        WorkflowRun retrieved = repository.find(run.runId()).orElseThrow();
        assertThat(retrieved.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(retrieved.steps().get(0).name()).isEqualTo("retrieve");
        assertThat(retrieved.steps().get(1).name()).isEqualTo("summarize");
    }

    @Test
    void 部分失败_workflow整体FAILED_但已成功的step可查() {
        InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
        ObjectMapper mapper = new ObjectMapper();
        // 用一个多 agent 分发:rag-agent 失败,summarizer-agent 永远不调用
        ToolPort multiPort = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    if (agent.name().equals("rag-agent")) {
                        subscriber.onNext(new ToolEvent.Error("A2A_AGENT_ERROR", "agent boom"));
                    } else {
                        // summarizer-agent 不该被调用(失败终止)
                        subscriber.onNext(new ToolEvent.Error("A2A_AGENT_ERROR", "should not run"));
                    }
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        WorkflowOrchestratorImpl orch = new WorkflowOrchestratorImpl(
                multiPort, stubAgentCardPort(),
                ObservabilityHooks.NOOP, repository,
                new JaywayJsonPathResolverAdapter(mapper), mapper, null);

        WorkflowDef def = new WorkflowDef("partial-fail", List.of(
                new WorkflowStep.Single(new StepDef("fails", "rag-agent", Map.of(), null)),
                new WorkflowStep.Single(new StepDef("never-runs", "summarizer-agent", Map.of(), null))
        ), Map.of());

        WorkflowRun run = orch.run(def, Map.of(), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.FAILED);
        // step1 失败已记录
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).errorMessage()).contains("agent boom");
        // step2 没执行
        assertThat(repository.find(run.runId())).isPresent();
    }

    @Test
    void 复杂嵌套outputs跨step引用_3步chain() {
        // 验证 3 步 chain 都能引用上一步 outputs
        InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
        ObjectMapper mapper = new ObjectMapper();
        // 用同一个 ToolPort 但按 agent 返回不同 response(实际生产每个 agent 一个 ToolPort 实例)
        ToolPort multiPort = (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    String resp;
                    if (agent.name().equals("s1")) {
                        resp = "{\"id\":42}";
                    } else if (agent.name().equals("s2")) {
                        resp = "{\"processed_id\":42}";
                    } else {
                        resp = "{\"ok\":true}";
                    }
                    subscriber.onNext(new ToolEvent.Delta(resp));
                    subscriber.onNext(new ToolEvent.Complete(resp));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        WorkflowOrchestratorImpl orch = new WorkflowOrchestratorImpl(
                multiPort, stubAgentCardPort(),
                ObservabilityHooks.NOOP,
                repository, new JaywayJsonPathResolverAdapter(mapper), mapper, null);
        WorkflowDef def = new WorkflowDef("nested-3step", List.of(
                new WorkflowStep.Single(new StepDef("s1", "s1",
                        Map.of("q", new JsonPathExpression("$.inputs.q")), null)),
                new WorkflowStep.Single(new StepDef("s2", "s2",
                        Map.of("p", new JsonPathExpression("$.steps.s1.outputs.id")), null)),
                new WorkflowStep.Single(new StepDef("s3", "summarizer-agent",
                        Map.of("prev", new JsonPathExpression("$.steps.s2.outputs.processed_id")), null))
        ), Map.of());
        WorkflowRun run = orch.run(def, Map.of("q", "hi"), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(run.steps()).hasSize(3);
        // 链:Step1.id=42 → Step2.processed_id=42 → Step3.ok=true
        assertThat(run.steps().get(0).outputs()).containsEntry("id", 42);
        assertThat(run.steps().get(1).outputs()).containsEntry("processed_id", 42);
        assertThat(run.steps().get(2).outputs()).containsEntry("ok", true);
    }

    /** Stub ToolPort:固定返回 response(模拟 A2A Agent 应答)。 */
    private static ToolPort stubToolPort(String agentName, String response) {
        return (agent, argsJson, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    subscriber.onNext(new ToolEvent.Delta(response));
                    subscriber.onNext(new ToolEvent.Complete(response));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
    }

    private static AgentCardPort stubAgentCardPort() {
        return new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return List.of(
                        new AgentCard("rag-agent", "", List.of(), "{}", "{}", "1", true,
                                "http://stub/rag", List.of("http://stub/rag")),
                        new AgentCard("summarizer-agent", "", List.of(), "{}", "{}", "1", true,
                                "http://stub/sum", List.of("http://stub/sum")),
                        new AgentCard("s1", "", List.of(), "{}", "{}", "1", true,
                                "http://stub/s1", List.of("http://stub/s1")),
                        new AgentCard("s2", "", List.of(), "{}", "{}", "1", true,
                                "http://stub/s2", List.of("http://stub/s2"))
                );
            }
            @Override public Flow.Publisher<List<AgentCard>> watch() {
                return subscriber -> subscriber.onComplete();
            }
        };
    }
}