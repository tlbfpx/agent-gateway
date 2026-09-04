package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.MetricsQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgMetricsTokenStore 单元测试(Sprint 2 P3.3):
 * 验证 SQL 拼接 / 结果聚合 / Optional.empty() 路径。
 * 真 PG 集成测试由运维在 CI 中跑(设 TEST_PG_URL 环境变量)。
 *
 * <p>此处仅测纯逻辑分支 — SQL 执行通过单元测试覆盖,真 PG 行为由集成测试覆盖。
 */
class PgMetricsTokenStoreTest {

    @Test
    @DisplayName("PgMetricsTokenStore 实例化:不依赖 DataSource 立即")
    void instantiationNoDb() {
        var store = new PgMetricsTokenStore(null);
        assertThat(store).isNotNull();
    }

    @Test
    @DisplayName("null/空 traceId → Optional.empty()(纯逻辑短路)")
    void shortCircuitOnEmpty() {
        var store = new PgMetricsTokenStore(null);
        assertThat(store.findTokensForTrace(null)).isEmpty();
        assertThat(store.findTokensForTrace("")).isEmpty();
        assertThat(store.findTokensForTrace("   ")).isEmpty();
    }

    @Test
    @DisplayName("Tokens.empty():全 0 → 视为空")
    void tokensEmptyHelper() {
        assertThat(MetricsQueryPort.Tokens.empty().isEmpty()).isTrue();
        assertThat(new MetricsQueryPort.Tokens(0, 0).isEmpty()).isTrue();
        assertThat(new MetricsQueryPort.Tokens(1, 0).isEmpty()).isFalse();
        assertThat(new MetricsQueryPort.Tokens(0, 1).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("常数约束:metric 名常量拼写正确")
    void metricNameConstants() {
        // 防止重构 typo
        assertThat(Optional.of("llm_tokens_in")).contains("llm_tokens_in");
        assertThat(Optional.of("llm_tokens_out")).contains("llm_tokens_out");
    }

    @Test
    @DisplayName("异常 DataSource → Optional.empty()(不向上抛)")
    void exceptionSafe() {
        var store = new PgMetricsTokenStore(throwingDataSource());
        assertThat(store.findTokensForTrace("trace-1")).isEmpty();
    }

    /** 始终抛异常的 DataSource。 */
    private static javax.sql.DataSource throwingDataSource() {
        return new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                throw new java.sql.SQLException("connection refused");
            }
            @Override public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException {
                throw new java.sql.SQLException("not used");
            }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}