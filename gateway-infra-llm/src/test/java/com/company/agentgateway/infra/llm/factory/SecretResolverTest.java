package com.company.agentgateway.infra.llm.factory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * SecretResolver 的行为规范测试。
 */
class SecretResolverTest {

    @Test
    void 非占位符原样返回() {
        EnvSecretResolver resolver = new EnvSecretResolver();
        assertThat(resolver.resolve("sk-test-123")).isEqualTo("sk-test-123");
    }

    @Test
    void 空字符串抛异常() {
        EnvSecretResolver resolver = new EnvSecretResolver();
        assertThatThrownBy(() -> resolver.resolve(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKeyRef must not be blank");
    }

    @Test
    void null抛异常() {
        EnvSecretResolver resolver = new EnvSecretResolver();
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKeyRef must not be blank");
    }

    @Test
    void 占位符格式正确但环境变量未设置时抛异常() {
        EnvSecretResolver resolver = new EnvSecretResolver();
        // 使用一个不太可能设置的环境变量
        String placeholder = "${SECRET:TEST_UNSET_ENV_12345}";
        assertThatThrownBy(() -> resolver.resolve(placeholder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret env not set");
    }

    @Test
    void 占位符格式错误不匹配时原样返回() {
        EnvSecretResolver resolver = new EnvSecretResolver();
        // 缺少结尾 }
        assertThat(resolver.resolve("${SECRET:INCOMPLETE")).isEqualTo("${SECRET:INCOMPLETE");
        // 错误前缀
        assertThat(resolver.resolve("${PASSWORD:KEY}")).isEqualTo("${PASSWORD:KEY}");
    }
}
