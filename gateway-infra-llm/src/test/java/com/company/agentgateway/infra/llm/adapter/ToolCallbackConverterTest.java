package com.company.agentgateway.infra.llm.adapter;

import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ToolCallbackConverter 单测(spec B §3.1.3):schema 解析/失败降级/列表转换。 */
class ToolCallbackConverterTest {

    @Test
    void 合法schema转换成功() {
        ToolDescriptor td = new ToolDescriptor("echo", "回显", "{\"type\":\"object\"}");
        var cb = ToolCallbackConverter.convertOne(td);
        assertThat(cb.getToolDefinition().name()).isEqualTo("echo");
        assertThat(cb.getToolDefinition().description()).isEqualTo("回显");
        assertThat(cb.getToolDefinition().inputSchema()).contains("object");
    }

    @Test
    void 畸形schema降级object不抛异常() {
        ToolDescriptor td = new ToolDescriptor("bad", "x", "{not valid json");
        var cb = ToolCallbackConverter.convertOne(td);
        assertThat(cb.getToolDefinition().inputSchema()).contains("object");
    }

    @Test
    void 空schema降级object() {
        ToolDescriptor td = new ToolDescriptor("empty", "x", "");
        var cb = ToolCallbackConverter.convertOne(td);
        assertThat(cb.getToolDefinition().inputSchema()).contains("object");
    }

    @Test
    void nullSchema降级object() {
        ToolDescriptor td = new ToolDescriptor("null", "x", null);
        var cb = ToolCallbackConverter.convertOne(td);
        assertThat(cb.getToolDefinition().inputSchema()).contains("object");
    }

    @Test
    void call方法抛UnsupportedOperationException_spec_b31() {
        ToolDescriptor td = new ToolDescriptor("x", "d", "{\"type\":\"object\"}");
        var cb = ToolCallbackConverter.convertOne(td);
        assertThatThrownBy(() -> cb.call("{}"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("internalToolExecutionEnabled");
    }

    @Test
    void convert整个列表_失败项被跳过其余正常() {
        var ok = new ToolDescriptor("ok", "good", "{\"type\":\"object\"}");
        var bad = new ToolDescriptor("bad", "x", "not json{");
        var empty = new ToolDescriptor("empty", "x", null);
        var cbs = ToolCallbackConverter.convert(List.of(ok, bad, empty));
        assertThat(cbs).hasSize(3);
        assertThat(cbs.stream().map(c -> c.getToolDefinition().name()).toList())
                .containsExactly("ok", "bad", "empty");
    }

    @Test
    void convert空列表返空() {
        assertThat(ToolCallbackConverter.convert(null)).isEmpty();
        assertThat(ToolCallbackConverter.convert(List.of())).isEmpty();
    }

    @Test
    void 输入schema中的properties被保留() {
        ToolDescriptor td = new ToolDescriptor("search", "搜索",
                "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
        var cb = ToolCallbackConverter.convertOne(td);
        assertThat(cb.getToolDefinition().inputSchema()).contains("properties").contains("q");
    }
}
