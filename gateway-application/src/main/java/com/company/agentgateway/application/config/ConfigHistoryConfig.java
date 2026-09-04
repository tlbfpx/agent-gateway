package com.company.agentgateway.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;

/** ConfigHistory 装配（models/api-keys 各一，保留20版）。 */
@Configuration
public class ConfigHistoryConfig {

    @Bean
    public ConfigHistory modelConfigHistory(
            @Value("${gateway.llm.registry-file:data/models.json}") String registryFile) {
        return new ConfigHistory(Path.of(registryFile), 20);
    }

    @Bean
    public ConfigHistory apiKeyConfigHistory(
            @Value("${gateway.security.key-file:data/api-keys.json}") String keyFile) {
        return new ConfigHistory(Path.of(keyFile), 20);
    }

}
