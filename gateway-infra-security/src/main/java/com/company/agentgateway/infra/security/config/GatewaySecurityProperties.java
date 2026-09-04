package com.company.agentgateway.infra.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** API Key 静态配置（gateway.api-keys.*）：重启不丢的持久化 Key 列表。 */
@ConfigurationProperties(prefix = "gateway")
public class GatewaySecurityProperties {

    private List<Map<String, Object>> apiKeys = new ArrayList<>();

    public List<Map<String, Object>> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<Map<String, Object>> apiKeys) {
        this.apiKeys = apiKeys;
    }
}
