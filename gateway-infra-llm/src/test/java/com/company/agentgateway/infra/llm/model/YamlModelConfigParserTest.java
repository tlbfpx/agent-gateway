package com.company.agentgateway.infra.llm.model;

import com.company.agentgateway.domain.model.Capability;
import com.company.agentgateway.domain.model.ModelDef;
import com.company.agentgateway.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlModelConfigParserTest {

    private final YamlModelConfigParser parser = new YamlModelConfigParser();

    @Test
    void shouldParseNormalYamlSuccessfully() {
        String yaml = """
                models:
                  - id: deepseek-chat
                    provider: deepseek
                    displayName: DeepSeek Chat
                    endpoint: https://api.deepseek.com
                    apiKeyRef: ${SECRET:DEEPSEEK_API_KEY}
                    capabilities: [FUNCTION_CALLING]
                    contextWindow: 64000
                    costPer1kIn: 0.14
                    costPer1kOut: 0.28
                    enabled: true
                    tenantScope: [all]
                  - id: gpt-4-vision
                    provider: openai
                    displayName: GPT-4 Vision
                    endpoint: https://api.openai.com
                    apiKeyRef: ${SECRET:OPENAI_API_KEY}
                    capabilities: [FUNCTION_CALLING, VISION]
                    contextWindow: 128000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.03
                    enabled: true
                    tenantScope: [tenant-a, tenant-b]
                """;

        List<ModelDef> models = parser.parse(yaml);

        assertThat(models).hasSize(2);

        ModelDef deepseek = models.get(0);
        assertThat(deepseek.id()).isEqualTo(new ModelId("deepseek-chat"));
        assertThat(deepseek.provider()).isEqualTo("deepseek");
        assertThat(deepseek.displayName()).isEqualTo("DeepSeek Chat");
        assertThat(deepseek.endpoint()).isEqualTo("https://api.deepseek.com");
        assertThat(deepseek.apiKeyRef()).isEqualTo("${SECRET:DEEPSEEK_API_KEY}");
        assertThat(deepseek.capabilities()).containsExactly(Capability.FUNCTION_CALLING);
        assertThat(deepseek.contextWindow()).isEqualTo(64000);
        assertThat(deepseek.costPer1kIn()).isEqualTo(new BigDecimal("0.14"));
        assertThat(deepseek.costPer1kOut()).isEqualTo(new BigDecimal("0.28"));
        assertThat(deepseek.enabled()).isTrue();
        assertThat(deepseek.tenantScope()).containsExactly("all");

        ModelDef gpt4 = models.get(1);
        assertThat(gpt4.id()).isEqualTo(new ModelId("gpt-4-vision"));
        assertThat(gpt4.capabilities()).containsExactlyInAnyOrder(Capability.FUNCTION_CALLING, Capability.VISION);
        assertThat(gpt4.contextWindow()).isEqualTo(128000);
        assertThat(gpt4.tenantScope()).containsExactly("tenant-a", "tenant-b");
    }

    @Test
    void shouldReturnEmptyListWhenNoModels() {
        String yaml = "models: []";
        List<ModelDef> models = parser.parse(yaml);
        assertThat(models).isEmpty();
    }

    @Test
    void shouldThrowExceptionWhenMissingRequiredFields() {
        String yaml = """
                models:
                  - id: incomplete-model
                    provider: test
                """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
    }

    @Test
    void shouldThrowExceptionWhenMalformedYaml() {
        String yaml = """
                models:
                  - id: test
                invalid yaml content
                """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse YAML");
    }

    @Test
    void shouldIgnoreUnknownCapabilities() {
        String yaml = """
                models:
                  - id: test-model
                    provider: test
                    displayName: Test
                    endpoint: https://api.test.com
                    apiKeyRef: key
                    capabilities: [FUNCTION_CALLING, UNKNOWN_CAP]
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                """;

        List<ModelDef> models = parser.parse(yaml);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).capabilities()).containsExactly(Capability.FUNCTION_CALLING);
    }

    @Test
    void shouldParseBigDecimalFromDouble() {
        String yaml = """
                models:
                  - id: test-model
                    provider: test
                    displayName: Test
                    endpoint: https://api.test.com
                    apiKeyRef: key
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.123456789
                    costPer1kOut: 0.987654321
                    enabled: true
                    tenantScope: [all]
                """;

        List<ModelDef> models = parser.parse(yaml);

        assertThat(models).hasSize(1);
        // SnakeYAML 会将数字解析为 Double，我们需确保转换为 BigDecimal 不丢失精度
        assertThat(models.get(0).costPer1kIn()).isEqualTo(new BigDecimal("0.123456789"));
        assertThat(models.get(0).costPer1kOut()).isEqualTo(new BigDecimal("0.987654321"));
    }

    @Test
    void shouldParseEmptyCapabilities() {
        String yaml = """
                models:
                  - id: test-model
                    provider: test
                    displayName: Test
                    endpoint: https://api.test.com
                    apiKeyRef: key
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: true
                    tenantScope: [all]
                """;

        List<ModelDef> models = parser.parse(yaml);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).capabilities()).isEmpty();
    }

    @Test
    void shouldParseDisabledModel() {
        String yaml = """
                models:
                  - id: test-model
                    provider: test
                    displayName: Test
                    endpoint: https://api.test.com
                    apiKeyRef: key
                    capabilities: []
                    contextWindow: 4000
                    costPer1kIn: 0.01
                    costPer1kOut: 0.02
                    enabled: false
                    tenantScope: [all]
                """;

        List<ModelDef> models = parser.parse(yaml);

        assertThat(models).hasSize(1);
        assertThat(models.get(0).enabled()).isFalse();
    }

    @Test
    void shouldReturnEmptyForBlankYaml() {
        // 空字符串 → 空列表（合法的「无模型」语义）
        assertThat(parser.parse("")).isEmpty();
        // 空白字符 → 空列表
        assertThat(parser.parse("   ")).isEmpty();
        // null → 空列表
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void shouldThrowForScalarYaml() {
        // 标量 → 抛 IllegalArgumentException（非 NPE）
        assertThatThrownBy(() -> parser.parse("just a scalar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a map at root but got String");
    }
}
