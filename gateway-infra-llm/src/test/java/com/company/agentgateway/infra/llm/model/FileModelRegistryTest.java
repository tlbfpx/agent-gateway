package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileModelRegistryTest {

    @Test
    void 从配置构建模型列表() {
        var registry = new FileModelRegistry(List.of(
                Map.of("id", "minimax-abab6.5s-chat", "provider", "minimax",
                        "endpoint", "https://api.minimax.chat/v1", "apiKeyRef", "sk-x",
                        "capabilities", List.of("FUNCTION_CALLING"), "contextWindow", 245760),
                Map.of("id", "deepseek-chat", "provider", "deepseek",
                        "endpoint", "https://api.deepseek.com", "apiKeyRef", "sk-y")));

        assertThat(registry.listModels()).hasSize(2);
        assertThat(registry.getModel(new ModelId("minimax-abab6.5s-chat"))).isPresent();
        assertThat(registry.getModel(new ModelId("deepseek-chat")).get().provider()).isEqualTo("deepseek");
    }

    @Test
    void 缺id或provider的条目被跳过() {
        var registry = new FileModelRegistry(List.of(
                Map.of("provider", "minimax"),               // 无 id
                Map.of("id", "x"),                            // 无 provider
                Map.of("id", "ok", "provider", "minimax")));  // 有效
        assertThat(registry.listModels()).hasSize(1);
    }

    @Test
    void enabledFalse不注册() {
        var registry = new FileModelRegistry(List.of(
                Map.of("id", "off", "provider", "minimax", "enabled", "false")));
        assertThat(registry.listModels()).isEmpty();
    }

    @Test
    void 坏配置不拖垮整个注册表() {
        var registry = new FileModelRegistry(List.of(
                Map.of("id", "bad", "provider", "minimax", "contextWindow", "not-a-number"),
                Map.of("id", "good", "provider", "minimax")));
        // bad 的 contextWindow 解析失败回退默认值（不抛）
        assertThat(registry.listModels()).hasSize(2);
    }

    @Test
    void 未知capability跳过() {
        var registry = new FileModelRegistry(List.of(
                Map.of("id", "m", "provider", "minimax",
                        "capabilities", List.of("FUNCTION_CALLING", "TELEPATHY"))));
        var def = registry.getModel(new ModelId("m")).orElseThrow();
        assertThat(def.supportsFunctionCalling()).isTrue();
        assertThat(def.capabilities()).hasSize(1);
    }
}
