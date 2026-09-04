package com.company.agentgateway.infra.persistence;

import com.company.agentgateway.domain.session.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageDtoTest {

    static Stream<Arguments> allMessageTypes() {
        return Stream.of(
                Arguments.of(new com.company.agentgateway.domain.session.UserMessage("hi")),
                Arguments.of(new com.company.agentgateway.domain.session.AssistantMessage("hello")),
                Arguments.of(new com.company.agentgateway.domain.session.ToolCallMessage("agent-x", "{\"q\":1}")),
                Arguments.of(new com.company.agentgateway.domain.session.ToolResultMessage("agent-x", "result", false)),
                Arguments.of(new com.company.agentgateway.domain.session.ToolResultMessage("agent-x", "[slimmed]", true))
        );
    }

    @ParameterizedTest
    @MethodSource("allMessageTypes")
    void 往返转换一致(Message original) {
        MessageDto dto = MessageDto.from(original);
        Message restored = dto.toDomain();
        assertThat(restored).isEqualTo(original);
        assertThat(dto.type()).isNotNull();
    }

    @Test
    void 未知type抛异常() {
        MessageDto bad = new MessageDto("ghost", "x", null, null, false);
        assertThatThrownBy(bad::toDomain)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown message type");
    }

    @Test
    void 各类型type字段正确() {
        assertThat(MessageDto.from(new com.company.agentgateway.domain.session.UserMessage("a")).type()).isEqualTo("user");
        assertThat(MessageDto.from(new com.company.agentgateway.domain.session.AssistantMessage("a")).type()).isEqualTo("assistant");
        assertThat(MessageDto.from(new com.company.agentgateway.domain.session.ToolCallMessage("a", "b")).type()).isEqualTo("tool_call");
        assertThat(MessageDto.from(new com.company.agentgateway.domain.session.ToolResultMessage("a", "b", false)).type()).isEqualTo("tool_result");
    }
}
