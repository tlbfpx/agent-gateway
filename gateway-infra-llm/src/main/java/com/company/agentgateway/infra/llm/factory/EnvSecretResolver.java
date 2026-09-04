package com.company.agentgateway.infra.llm.factory;

/**
 * 基于 System.getenv 的密钥解析器实现。
 * 支持 ${SECRET:ENV_NAME} 占位符格式。
 * 由 InfraLlmAutoConfiguration 装配（条件化，不直接 @Component）。
 */
public class EnvSecretResolver implements SecretResolver {

    private static final String PREFIX = "${SECRET:";
    private static final String SUFFIX = "}";

    @Override
    public String resolve(String apiKeyRef) {
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            throw new IllegalArgumentException("apiKeyRef must not be blank");
        }

        // 检查是否为占位符格式
        if (apiKeyRef.startsWith(PREFIX) && apiKeyRef.endsWith(SUFFIX)) {
            String envVar = apiKeyRef.substring(PREFIX.length(), apiKeyRef.length() - SUFFIX.length());
            String value = System.getenv(envVar);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("secret env not set: " + envVar);
            }
            return value;
        }

        // 非占位符：原样返回（测试用，生产环境不应使用明文）
        return apiKeyRef;
    }
}
