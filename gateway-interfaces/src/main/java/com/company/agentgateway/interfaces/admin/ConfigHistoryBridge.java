package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.application.config.ConfigHistory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 ConfigHistory.snapshot 钩子接到两个可写 store（interfaces 层可见双方）。 */
@Configuration
public class ConfigHistoryBridge {

    @Bean
    public Object configHistoryHooks(
            ConfigHistory modelConfigHistory,
            ConfigHistory apiKeyConfigHistory,
            ObjectProvider<com.company.agentgateway.infra.llm.model.JsonFileModelRegistry> modelReg,
            ObjectProvider<com.company.agentgateway.infra.security.JsonFileApiKeyStore> keyStore) {
        modelReg.ifAvailable(r -> r.setOnPersist(modelConfigHistory::snapshot));
        keyStore.ifAvailable(s -> s.setOnPersist(apiKeyConfigHistory::snapshot));
        return new Object();
    }
}
