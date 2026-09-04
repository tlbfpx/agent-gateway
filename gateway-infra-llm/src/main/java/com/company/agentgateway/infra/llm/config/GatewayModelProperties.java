package com.company.agentgateway.infra.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型列表配置（gateway.models.*）。切换/新增模型 = 在 application.yml 加一条，零代码。
 *
 * <pre>
 * gateway:
 *   models:
 *     - id: minimax-abab6.5s-chat
 *       provider: minimax
 *       endpoint: https://api.minimax.chat/v1
 *       apiKeyRef: ${MINIMAX_API_KEY}
 *       capabilities: [FUNCTION_CALLING]
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayModelProperties {

    private List<Map<String, Object>> models = new ArrayList<>();

    public List<Map<String, Object>> getModels() {
        return models;
    }

    public void setModels(List<Map<String, Object>> models) {
        this.models = models;
    }
}
