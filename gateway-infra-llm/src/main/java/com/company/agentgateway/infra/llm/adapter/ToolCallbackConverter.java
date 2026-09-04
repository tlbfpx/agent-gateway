package com.company.agentgateway.infra.llm.adapter;

import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolDescriptor → ToolCallback 转换器(spec B §3.1)。
 *
 * <p>仅声明 schema(name/description/inputSchema),不绑执行函数 —— 自研 runToolLoop 仍持有执行权。
 * Spring AI 的 internal tool execution 必须禁用,否则双重执行(此处仅声明)。
 *
 * <p>inputSchemaJson 解析失败:跳过该工具 + WARN 日志,不阻断其他工具(spec §3.1.3)。
 */
public final class ToolCallbackConverter {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallbackConverter() {}

    /** 转换整个工具列表;失败的工具被跳过。 */
    public static List<ToolCallback> convert(List<ToolDescriptor> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        List<ToolCallback> out = new ArrayList<>(tools.size());
        for (ToolDescriptor td : tools) {
            try {
                out.add(convertOne(td));
            } catch (Exception e) {
                log.warn("ToolDescriptor 转换失败,跳过(agent={}): {}", td.name(), e.getMessage());
            }
        }
        return out;
    }

    static ToolCallback convertOne(ToolDescriptor td) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(td.name())
                .description(td.description() == null ? "" : td.description())
                .inputSchema(resolveSchema(td))
                .build();
        // 仅声明 schema,不绑 call function —— Spring AI 不会自动执行,自研 runToolLoop 接管
        return new SchemaOnlyToolCallback(def);
    }

    /** inputSchemaJson 优先;空/无效则降级 {"type":"object"}。 */
    private static String resolveSchema(ToolDescriptor td) {
        String raw = td.inputSchemaJson();
        if (raw == null || raw.isBlank()) return "{\"type\":\"object\"}";
        try {
            JsonNode n = MAPPER.readTree(raw);
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            log.warn("工具 {} inputSchemaJson 非合法 JSON,降级空 schema: {}", td.name(), e.getMessage());
            return "{\"type\":\"object\"}";
        }
    }

    /** 仅 schema 声明,无 call function;Spring AI 内部执行时会跳过(null call)。 */
    static final class SchemaOnlyToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        SchemaOnlyToolCallback(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String input) {
            // 自研 runToolLoop 接管执行 —— 此处不应被调用
            throw new UnsupportedOperationException(
                    "SchemaOnlyToolCallback 不执行调用(internalToolExecutionEnabled 必须为 false)");
        }
    }
}