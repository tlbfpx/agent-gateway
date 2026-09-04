package com.company.agentgateway.infra.persistence.cache;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 语义缓存 schema 初始化(Sprint 4 P0):执行 schema-semantic-cache.sql。
 *
 * <p>与可观测性 schema 独立,条件装配(@ConditionalOnBean DataSource),由
 * SemanticCacheAutoConfiguration 触发。
 */
@ConditionalOnBean(DataSource.class)
public class SemanticCacheSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheSchemaInitializer.class);

    private final DataSource dataSource;

    public SemanticCacheSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-semantic-cache.sql"));
            log.info("semantic_cache schema 初始化完成(pgvector + semantic_cache 表 + HNSW)");
        } catch (Exception e) {
            log.error("semantic_cache schema 初始化失败: {}", e.getMessage(), e);
            // 失败不抛:用户可能不想启用缓存,可降级为关闭
        }
    }
}