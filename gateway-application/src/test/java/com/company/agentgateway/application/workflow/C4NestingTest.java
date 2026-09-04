package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.BranchDef;
import com.company.agentgateway.domain.workflow.CaseDef;
import com.company.agentgateway.domain.workflow.Join;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.StepRun;
import com.company.agentgateway.domain.workflow.SwitchDef;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * C4 嵌套 DAG 单测:CaseDef.step + SwitchDef.defaultStep 升级为 WorkflowStep,
 * OrchestratorImpl dispatch 链支持 case.step 嵌套 Single / Parallel / Switch(深度 ≤ 5)。
 * 无 mockito 依赖,直接用 Port + Card 自实现。
 */
class C4NestingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowRepository repo = new com.company.agentgateway.application.workflow.repo.InMemoryWorkflowRepository();
    private final JaywayJsonPathResolverAdapter resolver = new JaywayJsonPathResolverAdapter(mapper);

    @Test
    void case_step是Single_走原路径() {
        ToolPort port = stubPort("{\"v\":\"single-result\"}");
        AgentCardPort cards = stubCards("echo-agent");
        var orch = new WorkflowOrchestratorImpl(port, cards, ObservabilityHooks.NOOP, repo, resolver, mapper, null);

        var def = new WorkflowDef("t", List.of(
                new WorkflowStep.Switch(new SwitchDef("sw", new JsonPathExpression("$.inputs.x"),
                        List.of(new CaseDef("a", new WorkflowStep.Single(
                                new StepDef("a", "echo-agent", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000)))),
                        new WorkflowStep.Single(new StepDef("def", "echo-agent", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000))))),
                Map.of());
        var run = orch.run(def, Map.of("x", "a", "q", "hello"), InvocationCtx.NOOP);

        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).name()).isEqualTo("a");
    }

    @Test
    void case_step是Parallel_走parallelExecutor() {
        ToolPort port = stubPort("{\"v\":\"p-result\"}");
        AgentCardPort cards = stubCards("echo-agent");
        var orch = new WorkflowOrchestratorImpl(port, cards, ObservabilityHooks.NOOP, repo, resolver, mapper, null);

        var nestedParallel = new ParallelDef("nested-p", Join.ALL, List.of(
                new BranchDef("p1", "echo-agent", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000, 0),
                new BranchDef("p2", "echo-agent", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000, 0)));
        var def = new WorkflowDef("t", List.of(
                new WorkflowStep.Switch(new SwitchDef("sw", new JsonPathExpression("$.inputs.x"),
                        List.of(new CaseDef("a", new WorkflowStep.Parallel(nestedParallel))),
                        new WorkflowStep.Single(new StepDef("def", "echo-agent", Map.of("q", new JsonPathExpression("$.inputs.q")), 1000))))),
                Map.of());
        var run = orch.run(def, Map.of("x", "a", "q", "hello"), InvocationCtx.NOOP);

        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        // nested-p 跑出 2 branches → 2 step
        long nestedCount = run.steps().stream()
                .filter(s -> s.name().equals("p1") || s.name().equals("p2"))
                .count();
        assertThat(nestedCount).isEqualTo(2);
    }

    @Test
    void 嵌套深度超限_run失败并记录错误() {
        ToolPort port = stubPort("{}");
        AgentCardPort cards = stubCards("echo-agent");
        var orch = new WorkflowOrchestratorImpl(port, cards, ObservabilityHooks.NOOP, repo, resolver, mapper, null);

        // buildNested(6) 产生 depth 1..6 的 switch 链,第 6 层超限(上限 5)
        var def = new WorkflowDef("t", List.of(buildNested(6)), Map.of());

        var run = orch.run(def, Map.of("x", "a"), InvocationCtx.NOOP);

        // orchestrator 捕获 WorkflowRuntimeException → run FAILED + 末尾 FAILED StepRun
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.FAILED);
        StepRun last = run.steps().get(run.steps().size() - 1);
        assertThat(last.status()).isEqualTo(StepRun.Status.FAILED);
        assertThat(last.errorMessage()).contains("嵌套深度超过 5");
    }

    @Test
    void 嵌套子step带链路tag_供UI分组渲染() {
        ToolPort port = stubPort("{\"v\":\"nested\"}");
        AgentCardPort cards = stubCards("echo-agent");
        var orch = new WorkflowOrchestratorImpl(port, cards, ObservabilityHooks.NOOP, repo, resolver, mapper, null);

        // switch > parallel(2 branches):分支应带 parentIndex=0 + "sw>case:value=a>..." tag
        var nestedParallel = new ParallelDef("np", Join.ALL, List.of(
                new BranchDef("b1", "echo-agent", Map.of(), 1000, 0),
                new BranchDef("b2", "echo-agent", Map.of(), 1000, 0)));
        var def = new WorkflowDef("t", List.of(
                new WorkflowStep.Switch(new SwitchDef("sw", new JsonPathExpression("$.inputs.x"),
                        List.of(new CaseDef("a", new WorkflowStep.Parallel(nestedParallel))),
                        new WorkflowStep.Single(new StepDef("def", "echo-agent", Map.of(), 1000))))),
                Map.of());
        var run = orch.run(def, Map.of("x", "a"), InvocationCtx.NOOP);

        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        var tagged = run.steps().stream()
                .filter(s -> s.name().equals("b1") || s.name().equals("b2"))
                .toList();
        assertThat(tagged).hasSize(2);
        for (StepRun s : tagged) {
            assertThat(s.parentIndex()).isEqualTo(0);
            assertThat(s.branchName()).startsWith("sw>case:value=a");
        }
    }

    @Test
    void ParseService_case_step是map带def_自动识别为Single() {
        var yaml = "{\"name\":\"t\",\"steps\":[{\"switch\":{\"name\":\"sw\",\"key\":\"$.x\","
                + "\"cases\":[{\"value\":\"a\",\"step\":{\"name\":\"a\",\"agent\":\"echo\",\"inputs\":{\"q\":\"$.x\"}}}],"
                + "\"default\":{\"name\":\"def\",\"agent\":\"echo\",\"inputs\":{}}}}]}";
        var parser = new WorkflowParseService(mapper);
        var def = parser.parseJson(yaml);
        var sw = (WorkflowStep.Switch) def.steps().get(0);
        // case.step 是 flat map → 应被识别为 Single 包装
        assertThat(sw.def().cases().get(0).step()).isInstanceOf(WorkflowStep.Single.class);
    }

    private static WorkflowStep buildNested(int depth) {
        if (depth == 0) {
            return new WorkflowStep.Single(new StepDef("d", "echo-agent", Map.of(), 1000));
        }
        return new WorkflowStep.Switch(new SwitchDef("sw" + depth, new JsonPathExpression("$.inputs.x"),
                List.of(new CaseDef("a", buildNested(depth - 1))),
                buildNested(0)));
    }

    private static ToolPort stubPort(String response) {
        return (agent, args, ctx) -> subscriber1 -> {
            subscriber1.onSubscribe(new Flow.Subscription() {
                public void request(long n) {
                    subscriber1.onNext(new ToolEvent.Delta(response));
                    subscriber1.onNext(new ToolEvent.Complete(response));
                    subscriber1.onComplete();
                }
                public void cancel() {}
            });
        };
    }

    private static AgentCardPort stubCards(String... agentNames) {
        return new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return java.util.Arrays.stream(agentNames)
                        .map(n -> new AgentCard(n, "d", List.of(), "{}", "{}", "1", true,
                                "http://stub/" + n, List.of("http://stub/" + n)))
                        .toList();
            }
            @Override public Flow.Publisher<List<AgentCard>> watch() { return s -> s.onComplete(); }
        };
    }
}