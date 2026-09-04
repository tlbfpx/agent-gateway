package com.company.agentgateway.infra.persistence.config;

import com.company.agentgateway.domain.ratelimit.RateLimiter;
import com.company.agentgateway.infra.persistence.ratelimit.TokenBucketRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RateLimiter 自动装配（Round 19 §rate-limit）。
 *
 * <p>P0：{@link TokenBucketRateLimiter} 内存;R19+1 swap Pg。
 */
@Configuration
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter tokenBucketRateLimiter() {
        return new TokenBucketRateLimiter();
    }
}