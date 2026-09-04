package com.company.agentgateway.domain.model;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ModelDefTest {
    @Test
    void hasFunctionCallingCapability() {
        var m = new ModelDef(new ModelId("qwen-max"),
            "dashscope", "通义千问 Max", "https://...", "ref-1",
            Set.of(Capability.FUNCTION_CALLING), 32000,
            new BigDecimal("0.04"), new BigDecimal("0.12"), true, java.util.List.of("all"));
        assertThat(m.capabilities()).contains(Capability.FUNCTION_CALLING);
        assertThat(m.supportsFunctionCalling()).isTrue();
    }
    @Test
    void withoutFunctionCalling() {
        var m = new ModelDef(new ModelId("minimax-abab7"),
            "minimax", "MiniMax", "https://...", "ref-2",
            Set.of(), 8000,
            new BigDecimal("0.01"), new BigDecimal("0.03"), true, java.util.List.of("all"));
        assertThat(m.supportsFunctionCalling()).isFalse();
    }
}
