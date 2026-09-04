package com.company.agentgateway.infra.llm.port;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.orchestration.ToolDescriptor;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.infra.llm.model.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCapabilityFailoverTest {

    private static final ModelId FALLBACK_ID = new ModelId("qwen-max");

    private ModelDef model(String id, boolean supportsFc) {
        return new ModelDef(
                new ModelId(id), "openai-compatible", id, "https://endpoint", "${SECRET:K}",
                supportsFc ? Set.of(Capability.FUNCTION_CALLING) : Set.of(),
                4096, BigDecimal.ZERO, BigDecimal.ZERO, true, List.of("all"));
    }

    private ModelCapabilityFailover failover(ModelRegistry registry) {
        return new ModelCapabilityFailover(registry, FALLBACK_ID);
    }

    private List<ToolDescriptor> tools() {
        return List.of(new ToolDescriptor("t", "d", "{}"));
    }

    @Test
    void 无工具时不降级_即使模型缺FC() {
        ModelRegistry registry = mock(ModelRegistry.class);
        var selected = model("basic", false);
        var resolved = failover(registry).resolve(selected, List.of());
        assertThat(resolved).isSameAs(selected);
    }

    @Test
    void 模型支持FC且有工具时不降级() {
        ModelRegistry registry = mock(ModelRegistry.class);
        var selected = model("pro", true);
        var resolved = failover(registry).resolve(selected, tools());
        assertThat(resolved).isSameAs(selected);
    }

    @Test
    void 缺FC且有工具时降级到fallback() {
        ModelRegistry registry = mock(ModelRegistry.class);
        var selected = model("basic", false);
        var fallback = model("qwen-max", true);
        when(registry.getModel(FALLBACK_ID)).thenReturn(Optional.of(fallback));

        var resolved = failover(registry).resolve(selected, tools());

        assertThat(resolved).isSameAs(fallback);
    }

    @Test
    void fallback不存在抛IllegalArgumentException() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(FALLBACK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> failover(registry).resolve(model("basic", false), tools()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackToolModel not found");
    }

    @Test
    void fallback自身缺FC抛IllegalStateException() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.getModel(FALLBACK_ID)).thenReturn(Optional.of(model("qwen-max", false)));

        assertThatThrownBy(() -> failover(registry).resolve(model("basic", false), tools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FUNCTION_CALLING");
    }
}
