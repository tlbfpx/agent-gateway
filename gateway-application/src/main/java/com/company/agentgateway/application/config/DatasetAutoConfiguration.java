package com.company.agentgateway.application.config;

import com.company.agentgateway.application.dataset.DatasetService;
import com.company.agentgateway.application.dataset.EvalRunService;
import com.company.agentgateway.application.dataset.judge.LlmJudge;
import com.company.agentgateway.domain.dataset.Judge;
import com.company.agentgateway.domain.dataset.JudgeLlmPort;
import com.company.agentgateway.domain.orchestration.ChatClientPort;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;
import com.company.agentgateway.infra.persistence.dataset.InMemoryDatasetRepositories;
import com.company.agentgateway.infra.persistence.judge.StubJudge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据集 / 评测用例层自动装配（Round 13 §dataset-eval + R17 #1 LLM 真实调用）。
 */
@Configuration
public class DatasetAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(InMemoryDatasetRepositories.class)
    public InMemoryDatasetRepositories datasetStore() {
        return new InMemoryDatasetRepositories();
    }

    @Bean
    @ConditionalOnMissingBean(DatasetService.class)
    public DatasetService datasetService(InMemoryDatasetRepositories store) {
        return new DatasetService(store);
    }

    /** R17 #1:JudgeLlmPort 默认实现(经 ChatClientPort);无 ChatClient 时降级到 LlmJudge 不带 LLM */
    @Bean
    @ConditionalOnMissingBean(JudgeLlmPort.class)
    public JudgeLlmPort defaultJudgeLlmPort(ObjectProvider<ChatClientPort> chatClient) {
        ChatClientPort client = chatClient.getIfAvailable();
        if (client == null) {
            return null;  // 无 ChatClient → LlmJudge 走 Stub fallback
        }
        return new com.company.agentgateway.application.dataset.judge.DefaultJudgeLlmPort(client);
    }

    /**
     * R17 #1:Judge bean 升级为 LlmJudge(支持真实 LLM);JudgeLlmPort 可选注入。
     * ChatClientPort 存在时 → 真实 LLM;否则 → StubJudge fallback。
     */
    @Bean
    @ConditionalOnMissingBean(Judge.class)
    public Judge llmJudge(JudgeLlmPort llmPort) {
        return new LlmJudge(LlmJudge.DEFAULT_SYSTEM_PROMPT, new StubJudge(), llmPort);
    }

    @Bean
    @ConditionalOnMissingBean(EvalRunService.class)
    public EvalRunService evalRunService(
            InMemoryDatasetRepositories store, PromptVersionRepository promptRepo, Judge judge) {
        return new EvalRunService(store, promptRepo, judge);
    }
}
