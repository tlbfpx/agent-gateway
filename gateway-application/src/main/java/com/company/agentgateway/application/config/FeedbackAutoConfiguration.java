package com.company.agentgateway.application.config;

import com.company.agentgateway.application.feedback.FeedbackService;
import com.company.agentgateway.domain.feedback.FeedbackRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feedback 用例层自动装配（Round 11 §feedback-annotation §4）。
 *
 * <p>{@link FeedbackService} 单 bean,依赖由 infra-persistence 模块提供的
 * {@link FeedbackRepository}（P0: InMemory;R12: Pg）。
 */
@Configuration
public class FeedbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeedbackService.class)
    public FeedbackService feedbackService(FeedbackRepository repository) {
        return new FeedbackService(repository);
    }
}
