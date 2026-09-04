package com.company.agentgateway.infra.llm.factory;

/**
 * 密钥解析器接口。
 * 负责解析 ${SECRET:ENV_NAME} 占位符，从环境变量读取实际密钥。
 */
public interface SecretResolver {

    /**
     * 解析 API 密钥引用。
     * @param apiKeyRef 密钥引用，可能是占位符格式 ${SECRET:ENV_NAME} 或明文（仅测试用）
     * @return 解析后的密钥明文
     * @throws IllegalArgumentException 如果 apiKeyRef 为空
     * @throws IllegalStateException 如果占位符对应的环境变量未设置或为空
     */
    String resolve(String apiKeyRef);
}
