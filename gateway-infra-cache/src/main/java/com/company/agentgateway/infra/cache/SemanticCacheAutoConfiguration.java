package com.company.agentgateway.infra.cache;

import com.company.agentgateway.domain.cache.EmbeddingPort;
import com.company.agentgateway.domain.cache.SemanticCacheFacade;
import com.company.agentgateway.domain.cache.SemanticCachePort;
import com.company.agentgateway.infra.persistence.cache.PgSemanticCacheRepository;
import com.company.agentgateway.infra.persistence.cache.SemanticCacheSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 语义缓存自动装配(Sprint 4 P0)。
 *
 * <h2>装配条件</h2>
 * <ul>
 *   <li>主开关: {@code gateway.cache.enabled=true}(默认 false,避免无 Key 时启动失败)</li>
 *   <li>PG 仓储: {@code @ConditionalOnBean(DataSource.class)}(未配 PG 则禁用)</li>
 *   <li>Embedding 客户端:需 {@code gateway.cache.embedding-api-key} 非空</li>
 *   <li>服务 Bean: 需 EmbeddingPort 与 SemanticCachePort 同时存在</li>
 * </ul>
 *
 * <p>失败降级:任一依赖缺失 → 仅日志告警,不抛异常(网关应可无缓存启动)。
 */
@Configuration
@EnableConfigurationProperties(SemanticCacheProperties.class)
@ConditionalOnProperty(name = "gateway.cache.enabled", havingValue = "true")
public class SemanticCacheAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SemanticCacheSchemaInitializer semanticCacheSchemaInitializer(DataSource dataSource) {
        log.info("SemanticCache schema init enabled (DataSource present)");
        return new SemanticCacheSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SemanticCachePort.class)
    public SemanticCachePort semanticCachePort(DataSource dataSource, ObjectMapper objectMapper) {
        log.info("Wiring PgSemanticCacheRepository");
        return new PgSemanticCacheRepository(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.cache.embedding-api-key")
    @ConditionalOnMissingBean(EmbeddingPort.class)
    public EmbeddingPort embeddingPort(SemanticCacheProperties props) {
        log.info("Wiring OpenAiEmbeddingClient: model={} dim={}", props.getEmbeddingModel(), props.getEmbeddingDimensions());
        return new OpenAiEmbeddingClient(
                props.getEmbeddingApiKey(),
                props.getEmbeddingBaseUrl(),
                props.getEmbeddingModel(),
                props.getEmbeddingDimensions());
    }

    @Bean
    @ConditionalOnBean({SemanticCachePort.class, EmbeddingPort.class})
    @ConditionalOnMissingBean(SemanticCacheFacade.class)
    public SemanticCacheFacade semanticCacheService(SemanticCachePort port, EmbeddingPort embedding,
                                                   SemanticCacheProperties props,
                                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(
                () -> io.micrometer.core.instrument.Metrics.globalRegistry);
        log.info("Wiring SemanticCacheService: threshold={} ttl={}", props.getSimilarityThreshold(), props.getTtl());
        return new SemanticCacheService(port, embedding, props, meterRegistry);
    }
}