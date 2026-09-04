package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.workflow.BranchDef;
import com.company.agentgateway.domain.workflow.CaseDef;
import com.company.agentgateway.domain.workflow.Join;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.SwitchDef;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.company.agentgateway.domain.workflow.Join;
import com.company.agentgateway.domain.workflow.JsonPathExpression;
import com.company.agentgateway.domain.workflow.ParallelDef;
import com.company.agentgateway.domain.workflow.StepDef;
import com.company.agentgateway.domain.workflow.WorkflowDef;
import com.company.agentgateway.domain.workflow.WorkflowRuntimeException;
import com.company.agentgateway.domain.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL 解析服务(spec C1 §5):Jackson 读 WorkflowDef,做基础字段校验。
 *
 * <p>支持的 content-type:application/json(直接 Jackson)、application/yaml(SnakeYAML → Map → Jackson)。
 * 字段校验:step.name 唯一、agent 非空、inputs 值非空且以 $ 开头。
 */
public class WorkflowParseService {

    private final ObjectMapper objectMapper;

    public WorkflowParseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 直接 JSON body → WorkflowDef。
     *  sealed WorkflowStep 多态反序列化(domain 无 Jackson)→ 解析 steps[] 为 List<Map> 再手动转。 */
    public WorkflowDef parseJson(String body) {
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            return validate(toWorkflowDef(map));
        } catch (Exception e) {
            throw new WorkflowRuntimeException("?", "?", -1, "parse json failed: " + e.getMessage());
        }
    }

    /** YAML body → Map → WorkflowDef。 */
    @SuppressWarnings("unchecked")
    public WorkflowDef parseYaml(String yaml) {
        try {
            org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> map = y.load(yaml);
            return validate(toWorkflowDef(map));
        } catch (Exception e) {
            throw new WorkflowRuntimeException("?", "?", -1, "parse yaml failed: " + e.getMessage());
        }
    }

    /** Map 形式 step → sealed WorkflowStep:有 "parallel" 键 → Parallel,否则 Single。 */
    @SuppressWarnings("unchecked")
    private static WorkflowStep toStep(Map<String, Object> stepMap) {
        if (stepMap.containsKey("switch")) {
            Map<String, Object> sw = (Map<String, Object>) stepMap.get("switch");
            String name = (String) sw.get("name");
            String keyStr = (String) sw.get("key");
            JsonPathExpression key = new JsonPathExpression(keyStr);
            List<Map<String, Object>> csMap = (List<Map<String, Object>>) sw.get("cases");
            if (csMap == null || csMap.isEmpty()) {
                throw new WorkflowRuntimeException("?", name, -1, "switch.cases 不能为空");
            }
            List<CaseDef> cases = new ArrayList<>();
            for (Map<String, Object> c : csMap) {
                // C4:case.step 可为 def/parallel/switch 任意形态 → 统一走 toStep(递归解析)
                WorkflowStep caseStep = toStep((Map<String, Object>) c.get("step"));
                Object value = c.get("value");
                cases.add(new CaseDef(value, caseStep));
            }
            Map<String, Object> defMap = (Map<String, Object>) sw.get("default");
            if (defMap == null) {
                throw new WorkflowRuntimeException("?", name, -1, "switch.default 必填(spec C3)");
            }
            WorkflowStep defaultStep = toStep(defMap);
            return new WorkflowStep.Switch(new SwitchDef(name, key, cases, defaultStep));
        }
        if (stepMap.containsKey("parallel")) {
            Map<String, Object> p = (Map<String, Object>) stepMap.get("parallel");
            String name = (String) p.get("name");
            String joinStr = (String) p.get("join");
            Join join = joinStr == null ? Join.ALL : Join.of(joinStr);
            List<Map<String, Object>> brMap = (List<Map<String, Object>>) p.get("branches");
            List<BranchDef> branches = new ArrayList<>();
            for (Map<String, Object> b : brMap) {
                branches.add(new BranchDef(
                        (String) b.get("name"),
                        (String) b.get("agent"),
                        toJsonPathInputs((Map<String, Object>) b.get("inputs")),
                        b.get("timeoutMs") == null ? null : ((Number) b.get("timeoutMs")).intValue(),
                        b.get("retryCount") == null ? null : ((Number) b.get("retryCount")).intValue()
                ));
            }
            return new WorkflowStep.Parallel(new ParallelDef(name, join, branches));
        }
        // 兼容 step 形态 {def: {name, agent, inputs, timeoutMs, retryCount}};
        // C3 case/default 的 flat 形态 {name, agent, inputs}(无 def 包装)也在此兼容
        Map<String, Object> defMap = (Map<String, Object>) stepMap.get("def");
        if (defMap == null && stepMap.containsKey("agent")) {
            defMap = stepMap;
        }
        if (defMap == null) {
            throw new WorkflowRuntimeException("?", "?", -1,
                    "step missing 'def' or 'parallel' field: " + stepMap);
        }
        return new WorkflowStep.Single(parseStepDef(defMap));
    }

    /** 从 {name, agent, inputs, timeoutMs, retryCount} map 解析 StepDef — 复用于 Single 与 Switch case/default。 */
    private static StepDef parseStepDef(Map<String, Object> defMap) {
        return new StepDef(
                (String) defMap.get("name"),
                (String) defMap.get("agent"),
                toJsonPathInputs((Map<String, Object>) defMap.get("inputs")),
                defMap.get("timeoutMs") == null ? null : ((Number) defMap.get("timeoutMs")).intValue()
        );
    }

    /** Map<String, Object> → Map<String, JsonPathExpression>(value 必为 $ 开头的字符串)。 */
    private static Map<String, JsonPathExpression> toJsonPathInputs(Map<String, Object> raw) {
        if (raw == null) return new HashMap<>();
        Map<String, JsonPathExpression> out = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof JsonPathExpression j) { out.put(e.getKey(), j); continue; }
            String vs = v.toString();
            // 字面量(spec C2):非 $ 开头,用 literal 标记
            if (vs.startsWith("$")) {
                out.put(e.getKey(), new JsonPathExpression(vs));
            } else {
                out.put(e.getKey(), JsonPathExpression.literal(vs));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static WorkflowDef toWorkflowDef(Map<String, Object> map) {
        String name = (String) map.get("name");
        Map<String, Object> inputs = (Map<String, Object>) map.getOrDefault("defaultInputs", new HashMap<>());
        List<Map<String, Object>> stepsMap = (List<Map<String, Object>>) map.get("steps");
        List<WorkflowStep> steps = new ArrayList<>();
        if (stepsMap != null) for (Map<String, Object> sm : stepsMap) steps.add(toStep(sm));
        return new WorkflowDef(name, steps, inputs);
    }

    WorkflowDef validate(WorkflowDef def) {
        if (def.name() == null || def.name().isBlank()) {
            throw new WorkflowRuntimeException("?", "?", -1, "workflow.name is required");
        }
        if (def.steps().isEmpty()) {
            throw new WorkflowRuntimeException(def.name(), "?", -1, "workflow.steps is empty");
        }
        // step name 唯一(spec C2.1:Single + Parallel 共享 name 空间)
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < def.steps().size(); i++) {
            var step = def.steps().get(i);
            String stepName = null;
            String agentName = null;
            if (step instanceof WorkflowStep.Single s) {
                stepName = s.def().name();
                agentName = s.def().agent();
            } else if (step instanceof WorkflowStep.Parallel p) {
                stepName = p.def().name();
                // parallel 节点本身不绑 agent;分支 agent 由 branches[].agent 决定
                for (int bi = 0; bi < p.def().branches().size(); bi++) {
                    BranchDef b = p.def().branches().get(bi);
                    if (b.name() == null || b.name().isBlank()) {
                        throw new WorkflowRuntimeException(def.name(), p.def().name(), i,
                                "parallel.branches[" + bi + "].name required");
                    }
                    if (b.agent() == null || b.agent().isBlank()) {
                        throw new WorkflowRuntimeException(def.name(), p.def().name(), i,
                                "parallel.branches[" + bi + "].agent required");
                    }
                }
            } else if (step instanceof WorkflowStep.Switch sw) {
                stepName = sw.def().name();
            } else {
                throw new WorkflowRuntimeException(def.name(), "?", i,
                        "unknown step type: " + step.getClass().getSimpleName());
            }
            if (stepName == null || stepName.isBlank()) {
                throw new WorkflowRuntimeException(def.name(), "?", i, "step[" + i + "].name required");
            }
            if (!names.add(stepName)) {
                throw new WorkflowRuntimeException(def.name(), stepName, i, "duplicate step name: " + stepName);
            }
            // Single 节点检查 agent(parallel 节点不绑 agent,已在上面分支循环内检查)
            if (agentName != null && agentName.isBlank()) {
                throw new WorkflowRuntimeException(def.name(), stepName, i, "step.agent required");
            }
        }
        return def;
    }
}