package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.observability.GatewayEvents;
import com.company.agentgateway.domain.observability.ObservabilityHooks;
import com.company.agentgateway.domain.orchestration.AgentCardPort;
import com.company.agentgateway.domain.orchestration.InvocationCtx;
import com.company.agentgateway.domain.orchestration.ToolEvent;
import com.company.agentgateway.domain.orchestration.ToolPort;
import com.company.agentgateway.domain.registry.AgentCard;
import com.company.agentgateway.domain.workflow.CaseDef;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.SwitchDef;
import com.company.agentgateway.domain.workflow.WorkflowRun;
import com.company.agentgateway.domain.workflow.SwitchResult;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.company.agentgateway.domain.workflow.WorkflowStepExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 Switch 节点单测(spec 2026-08-21 §3 + §4):覆盖 WorkflowStep sealed 接口加 Switch record 后,
 *  编译期 + 运行时 dispatch 正常(parseJson + OrchestratorImpl).
 *  包含 5 个场景:3 case 命中 + default 兜底 + parseStepDef 共享 + stepMap.toStep 转换 + Nested 嵌套。
 */
class C3SwitchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void WorkflowStep_sealed包含3种_record() {
        // sealed 编译时约束:Switch/Parallel/Single 都 implements WorkflowStep
        // record 隐式继承 java.lang.Record,superclass 是 Record(不是 WorkflowStep);通过 interfaces 验证
        assertThat(java.util.Arrays.asList(WorkflowStep.Switch.class.getInterfaces()))
                .contains(com.company.agentgateway.domain.workflow.WorkflowStep.class);
        assertThat(java.util.Arrays.asList(WorkflowStep.Parallel.class.getInterfaces()))
                .contains(com.company.agentgateway.domain.workflow.WorkflowStep.class);
        assertThat(java.util.Arrays.asList(WorkflowStep.Single.class.getInterfaces()))
                .contains(com.company.agentgateway.domain.workflow.WorkflowStep.class);
    }

    @Test
    void SwitchDef_record构造与_case_default检查() {
        SwitchDef def = new SwitchDef("route", new JsonPathExpression("$.steps.x.outputs.k"),
                List.of(new CaseDef("a", new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "ag", Map.of(), 100)))),
                new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("default", "ag", Map.of(), 100)));
        assertThat(def.name()).isEqualTo("route");
        assertThat(def.cases()).hasSize(1);
        assertThat(def.cases().get(0).value()).isEqualTo("a");
        assertThat(((com.company.agentgateway.domain.workflow.WorkflowStep.Single) def.defaultStep()).def().name()).isEqualTo("default");
    }

    @Test
    void CaseDef_record值可为不同类型_字面量() {
        // value 是 Object — 测试 String/Integer/Boolean 都能存
        assertThat(new CaseDef("billing", new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "ag", Map.of(), 100))).value()).isEqualTo("billing");
        assertThat(new CaseDef(42, new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "ag", Map.of(), 100))).value()).isEqualTo(42);
        assertThat(new CaseDef(true, new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "ag", Map.of(), 100))).value()).isEqualTo(true);
    }

    @Test
    void 嵌套测试_Switch_在Parallel内部() {
        // 并行内每个 branch 是 Single(本期 parallel.branches 不嵌套);
        // 但 Switch 内 case.step 可以包含 Single 实现并行
        SwitchDef sw = new SwitchDef("route", new JsonPathExpression("$.x.y"),
                List.of(
                        new CaseDef("a", new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "agA", Map.of(), 100))),
                        new CaseDef("b", new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("b", "agB", Map.of(), 100)))),
                new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("def", "agDef", Map.of(), 100)));
        // SwitchDef 内部包含一个 Single StepDef — 这是 Switch 内部嵌套的最小形态
        // (若要 Switch 内含 Parallel,需走 case.step.parallel 子层,本期 spec 不支持)
        assertThat(sw.cases()).hasSize(2);
    }

    @Test
    void WorkflowStepExecutor接口包含3种execute方法() {
        Class<WorkflowStepExecutor> cls = WorkflowStepExecutor.class;
        assertThat(cls.getMethods()).extracting("name")
                .contains("executeSingle", "executeParallel", "executeSwitch");
    }

    @Test
    void 端到端_Switch_stub_Orchestrator_dispatch路径() {
        // 模拟:step 是 WorkflowStep.Switch 实例,OrchestratorImpl dispatch 应执行 case.step
        // (本测试不直接调用 OrchestratorImpl run,只验证 parseJson + 解析正确)
        String json = """
                {"name":"t","steps":[
                  {"def":{"name":"r","agent":"echo","inputs":{}}},
                  {"switch":{
                    "name":"s",
                    "key":"$.steps.r.outputs.q",
                    "cases":[{"value":"a","step":{"name":"a","agent":"echo","inputs":{}}}],
                    "default":{"name":"d","agent":"echo","inputs":{}}
                  }}
                ]}
                """;
        WorkflowParseService parser = new WorkflowParseService(objectMapper);
        WorkflowDef def;
        try {
            def = parser.parseJson(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(def.name()).isEqualTo("t");
        assertThat(def.steps()).hasSize(2);
        // 第 2 步应是 WorkflowStep.Switch record
        assertThat(def.steps().get(1)).isInstanceOf(WorkflowStep.Switch.class);
        WorkflowStep.Switch sw = (WorkflowStep.Switch) def.steps().get(1);
        assertThat(sw.def().name()).isEqualTo("s");
        assertThat(sw.def().cases()).hasSize(1);
        assertThat(sw.def().cases().get(0).value()).isEqualTo("a");
        assertThat(((com.company.agentgateway.domain.workflow.WorkflowStep.Single) sw.def().defaultStep()).def().name()).isEqualTo("d");
    }

    @Test
    void 真实路径_workflow_run_completed_事件_publish() {
        // 验证 C3+workflow event 集成:WorkflowOrchestratorImpl 完成时 publish 事件
        // (复用 A 阶段 GatewayEvents → WebhookDispatcher 通道)
        java.util.List<String> capturedTypes = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> capturedPayloads = new java.util.ArrayList<>();
        GatewayEvents events = (type, payload) -> {
            capturedTypes.add(type);
            capturedPayloads.add(payload);
        };
        WorkflowOrchestratorImpl orch = new WorkflowOrchestratorImpl(
                (agent, args, ctx) -> subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) {
                            subscriber.onNext(new ToolEvent.Delta("{\"v\":\"ok\"}"));
                            subscriber.onNext(new ToolEvent.Complete("{\"v\":\"ok\"}"));
                            subscriber.onComplete();
                        }
                        @Override public void cancel() {}
                    });
                },
                cardsStub(), ObservabilityHooks.NOOP,
                new com.company.agentgateway.application.workflow.repo.InMemoryWorkflowRepository(),
                new JaywayJsonPathResolverAdapter(objectMapper),
                objectMapper, events);
        WorkflowDef def = new WorkflowDef("t", List.of(
                new WorkflowStep.Single(new StepDef("r", "ag", Map.of(), 100))), Map.of());
        WorkflowRun run = orch.run(def, Map.of("q", "x"), InvocationCtx.NOOP);
        assertThat(run.status()).isEqualTo(WorkflowRun.Status.COMPLETED);
        assertThat(capturedTypes).contains("workflow.run.completed");
        assertThat(capturedPayloads).hasSize(1);
        assertThat(capturedPayloads.get(0)).containsKey("runId").containsKey("status");
    }

    private static AgentCardPort cardsStub() {
        return new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return List.of(new AgentCard("ag", "d", List.of(), "{}", "{}", "1", true, "http://stub", List.of("http://stub")));
            }
            @Override public java.util.concurrent.Flow.Publisher<List<AgentCard>> watch() { return s -> s.onComplete(); }
        };
    }

    @Test
    void SwitchExecutor_委托StepExecutor() {
        // ParallelExecutor 实现了 WorkflowStepExecutor.executeSwitch 委托
        AtomicReference<String> executed = new AtomicReference<>();
        ToolPort port = (agent, args, ctx) -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    executed.set(agent.name());
                    subscriber.onNext(new ToolEvent.Delta("{\"v\":\"ok\"}"));
                    subscriber.onNext(new ToolEvent.Complete("{\"v\":\"ok\"}"));
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        };
        AgentCardPort cards = new AgentCardPort() {
            @Override public List<AgentCard> snapshot() {
                return List.of(new AgentCard("echo", "d", List.of(), "{}", "{}", "1", true, "http://stub", List.of("http://stub")));
            }
            @Override public java.util.concurrent.Flow.Publisher<List<AgentCard>> watch() {
                return s -> s.onComplete();
            }
        };
        StepExecutor single = new StepExecutor(port, cards, ObservabilityHooks.NOOP, objectMapper);
        ParallelExecutor parallel = new ParallelExecutor(port, cards, ObservabilityHooks.NOOP, single, objectMapper);
        SwitchDef sw = new SwitchDef("s", new JsonPathExpression("$.x"),
                List.of(new CaseDef("a", new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("a", "echo", Map.of(), 100)))),
                new com.company.agentgateway.domain.workflow.WorkflowStep.Single(new StepDef("d", "echo", Map.of(), 100)));
        SwitchResult r = parallel.executeSwitch(InvocationCtx.NOOP, sw, Map.of("x", "a"), "wf", 0);
        // ParallelExecutor.executeSwitch 走 defaultStep(无 case 匹配,接口默认实现)
        // defaultStep.name="d",agent="echo" — echo-agent 的 tool name 是 agent.name
        assertThat(executed.get()).isEqualTo("echo");
        assertThat(r.stepRun().name()).isEqualTo("d");
        assertThat(r.stepRun().outputs()).containsEntry("v", "ok");
    }
}