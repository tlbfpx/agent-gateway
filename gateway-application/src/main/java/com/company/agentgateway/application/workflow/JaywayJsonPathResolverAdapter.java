package com.company.agentgateway.application.workflow;

import com.company.agentgateway.domain.workflow.JsonPathResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.util.HashMap;
import java.util.Map;

/**
 * Jayway json-path 实现(spec C1 §4):把整个运行时 context 序列化为 JSON 字符串,
 * 由 jayway 在 String 上下文中解析(避免 ObjectNode 类型不匹配问题)。
 *
 * <p>支持路径:$.inputs.&lt;key&gt; 与 $.steps.&lt;stepName&gt;.outputs.&lt;key&gt;。
 * 路径无法解析时抛 WorkflowRuntimeException(spec C1 §6 fail-fast)。
 */
public class JaywayJsonPathResolverAdapter implements JsonPathResolver {

    private static final Configuration CONFIG = Configuration.defaultConfiguration();
    private final ObjectMapper objectMapper;

    public JaywayJsonPathResolverAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object resolve(String jsonPath, Map<String, Object> context) {
        // 字面量路径(spec C2):不是 JSONPath,直接返回字符串原值
        if (jsonPath == null || !jsonPath.startsWith("$")) {
            return jsonPath;
        }
        String document;
        try {
            document = objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new com.company.agentgateway.domain.workflow.WorkflowRuntimeException(
                    "?", "?", -1, "context serialize failed: " + e.getMessage());
        }
        DocumentContext parsed;
        try {
            parsed = JsonPath.using(CONFIG).parse(document);
        } catch (Exception e) {
            throw new com.company.agentgateway.domain.workflow.WorkflowRuntimeException(
                    "?", "?", -1, "JsonPath parse failed: " + jsonPath + " (" + e.getMessage() + ")");
        }
        Object result;
        try {
            result = parsed.read(jsonPath);
        } catch (Exception e) {
            throw new com.company.agentgateway.domain.workflow.WorkflowRuntimeException(
                    "?", "?", -1, "JsonPath context not satisfied: " + jsonPath + " (" + e.getMessage() + ")");
        }
        if (result == null) {
            throw new com.company.agentgateway.domain.workflow.WorkflowRuntimeException(
                    "?", "?", -1, "JsonPath context not satisfied: " + jsonPath);
        }
        // jayway 在 String 上下文下返回 List / Map / String / Number
        if (result instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        if (result instanceof Iterable<?> iter) {
            // 转为 List(覆盖 JSONArray 等 Iterable)
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object o : iter) {
                list.add(o);
            }
            return list;
        }
        return result;
    }
}