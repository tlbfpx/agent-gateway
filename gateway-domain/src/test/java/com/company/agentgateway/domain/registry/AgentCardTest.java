package com.company.agentgateway.domain.registry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentCardTest {
    @Test
    void buildsCardWithStringSchemas() {
        var card = new AgentCard("hr-agent", "请假助手",
            java.util.List.of("请假"), "{}", "{}", "1.0.0", true,
            "https://hr.example.com/a2a/invoke");
        assertThat(card.name()).isEqualTo("hr-agent");
        assertThat(card.available()).isTrue();
        assertThat(card.endpointUrl()).isEqualTo("https://hr.example.com/a2a/invoke");
    }

    @Test
    void endpointUrlMayBeNullWhenAddressUnknown() {
        var card = new AgentCard("orphan-agent", "无地址",
            java.util.List.of(), "{}", "{}", "0.0.1", false, null);
        assertThat(card.endpointUrl()).isNull();
    }
}
