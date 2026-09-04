package com.company.agentgateway.infra.persistence.replay;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Replay schema 初始化(Sprint 2 P0):执行 schema-replay.sql。
 *
 * <p>条件装配(@ConditionalOnBean DataSource);幂等 CREATE TABLE IF NOT EXISTS。
 */
@ConditionalOnBean(DataSource.class)
public class ReplaySchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(ReplaySchemaInitializer.class);

    private final DataSource dataSource;

    public ReplaySchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initialize() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-replay.sql"));
            log.info("replay schema 初始化完成(trace_payloads + replay_jobs)");
        } catch (Exception e) {
            log.error("replay schema 初始化失败: {}", e.getMessage(), e);
        }
    }
}