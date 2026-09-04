package com.company.agentgateway.application.config;

import com.company.agentgateway.application.prompt.ABTestService;
import com.company.agentgateway.application.prompt.PromptTemplateService;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 用例层自动装配（Round 12 #prompt-version）。
 */
@Configuration
public class PromptAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PromptTemplateService.class)
    public PromptTemplateService promptTemplateService(
            PromptTemplateRepository templateRepo, PromptVersionRepository versionRepo) {
        return new PromptTemplateService(templateRepo, versionRepo);
    }

    @Bean
    @ConditionalOnMissingBean(ABTestService.class)
    public ABTestService abTestService(PromptVersionRepository versionRepo) {
        return new ABTestService(versionRepo);
    }
}
