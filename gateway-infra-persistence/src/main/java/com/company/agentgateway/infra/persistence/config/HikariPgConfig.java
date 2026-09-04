package com.company.agentgateway.infra.persistence.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * HikariCP 连接池配置（spec 2026-09-02 §hikari-pool §3）。
 *
 * <p>Round 16 #4:替换 DriverManagerDataSource(P0 demo 用),生产用 HikariCP。
 * 由 {@code observability.storage.enabled=true} + {@code observability.pg.url} 触发。
 *
 * <p>核心参数：
 * <ul>
 *   <li>maximumPoolSize:20(R6 文档:单实例 gateway 100 req/s 下足够)</li>
 *   <li>minimumIdle:5(避免冷启动延迟)</li>
 *   <li>connectionTimeout:5s</li>
 *   <li>idleTimeout:10min</li>
 *   <li>leakDetectionThreshold:30s(开发环境检测连接泄漏)</li>
 *   <li>maxLifetime:30min(超过 PG 默认 2h 但避免单连接长期使用)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
public class HikariPgConfig {

    /**
     * 绑定 application.yml 的 {@code observability.pg.*} 配置:
     * <pre>
     * observability:
     *   storage:
     *     enabled: true
     *   pg:
     *     url: jdbc:postgresql://localhost:5432/agent_gateway
     *     username: agentgateway
     *     password: agentgateway
     *     pool-size: 20
     * </pre>
     */
    @Bean
    @ConfigurationProperties(prefix = "observability.pg")
    public HikariPgProperties hikariPgProperties() {
        return new HikariPgProperties();
    }

    @Bean(destroyMethod = "close")
    public DataSource observabilityDataSource(HikariPgProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        cfg.setMaximumPoolSize(props.getPoolSize());
        cfg.setMinimumIdle(Math.max(2, props.getPoolSize() / 4));
        cfg.setConnectionTimeout(5_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setLeakDetectionThreshold(30_000);
        cfg.setPoolName("agent-gateway-pg");
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }

    @Bean
    public JdbcTemplate observabilityJdbcTemplate(DataSource observabilityDataSource) {
        return new JdbcTemplate(observabilityDataSource);
    }

    /** 配置属性 record(由 {@code @ConfigurationProperties} 填充)。 */
    public static class HikariPgProperties {
        private String url;
        private String username;
        private String password;
        private int poolSize = 20;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }
}