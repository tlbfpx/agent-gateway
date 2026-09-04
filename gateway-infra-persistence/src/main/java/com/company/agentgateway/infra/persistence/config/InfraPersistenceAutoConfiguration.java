package com.company.agentgateway.infra.persistence.config;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.observability.AlertStore;
import com.company.agentgateway.domain.observability.MetricQueryRepository;
import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.workflow.WorkflowDefinitionRepository;
import com.company.agentgateway.domain.workflow.WorkflowRepository;
import com.company.agentgateway.domain.observability.SpanQueryRepository;
import com.company.agentgateway.domain.replay.PayloadCaptureHelper;
import com.company.agentgateway.domain.replay.PayloadCapturePort;
import com.company.agentgateway.infra.persistence.InMemorySessionRepository;
import com.company.agentgateway.infra.persistence.observability.PgAlertStore;
import com.company.agentgateway.infra.persistence.observability.PgAuditStore;
import com.company.agentgateway.infra.persistence.observability.PgMetricsStore;
import com.company.agentgateway.infra.persistence.observability.PgSchemaInitializer;
import com.company.agentgateway.infra.persistence.observability.PgSpanStore;
import com.company.agentgateway.infra.persistence.workflow.PgWorkflowDefinitionRepository;
import com.company.agentgateway.infra.persistence.workflow.PgWorkflowRepository;
import com.company.agentgateway.infra.persistence.replay.PgPayloadStore;
import com.company.agentgateway.infra.persistence.replay.PayloadCipher;
import com.company.agentgateway.infra.persistence.replay.NoOpPayloadStore;
import com.company.agentgateway.infra.persistence.replay.ReplaySchemaInitializer;
import com.company.agentgateway.infra.persistence.observability.NoOpSpanStore;
import com.company.agentgateway.infra.persistence.observability.NoOpSpanWriter;
import com.company.agentgateway.infra.persistence.observability.SpanWriter;
import com.company.agentgateway.infra.persistence.feedback.InMemoryFeedbackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * infra-persistence 运行期装配（条件装配，与 multi-model/a2a 模式一致）。
 *
 * <ul>
 *   <li>有 redis.addr → RedisConnectionFactory + StringRedisTemplate + RedisSessionRepository</li>
 *   <li>无 redis.addr → InMemorySessionRepository（默认，开发/测试/小规模）</li>
 *   <li>有 observability.storage.jdbc-url → PG/TimescaleDB 可观测性存储
 *       （spec 2026-08-19 §4.5：schema 幂等初始化 + Span/Metrics/Alert/Audit 四个 store）；无则全部 InMemory 降级</li>
 * </ul>
 */
// @AutoConfiguration:进入自动配置排序图,observability 的 @ConditionalOnBean(SpanWriter)依赖此顺序
// 排序：PG 持久化 bean（billing/quota/budget/rbac）必须先于 security(BillingQuota/InfraSecurity)
// 的 InMemory @ConditionalOnMissingBean 降级 bean 注册，否则 PG 实现永远不生效
@org.springframework.boot.autoconfigure.AutoConfiguration(beforeName = {
        "com.company.agentgateway.application.config.BillingQuotaAutoConfiguration",
        "com.company.agentgateway.infra.security.config.InfraSecurityAutoConfiguration"
})

public class InfraPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "redis.addr")
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${redis.addr}") String addr) {
        String[] hostPort = addr.split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 6379;
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    }

    @Bean
    @ConditionalOnProperty(name = "redis.addr")
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    @Bean
    @ConditionalOnProperty(name = "redis.addr")
    public com.company.agentgateway.domain.orchestration.SessionRepository redisSessionRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new com.company.agentgateway.infra.persistence.RedisSessionRepository(redis, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.orchestration.SessionRepository.class)
    @ConditionalOnProperty(name = "redis.addr", matchIfMissing = true)
    public com.company.agentgateway.domain.orchestration.SessionRepository inMemorySessionRepository() {
        return new InMemorySessionRepository();
    }

    /** 审计仓库：PG 优先，否则 InMemory append-only。 */
    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public AuditRepository pgAuditStore(JdbcTemplate jdbc) {
        return new PgAuditStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(AuditRepository.class)
    public AuditRepository inMemoryAuditRepository() {
        return new com.company.agentgateway.infra.persistence.audit.InMemoryAuditRepository();
    }

    // ================= 计费/配额/预算 + RBAC PG 持久化（add-pg-persistence） =================

    /** schema 幂等初始化（billing_records/budgets/quota_counters/rbac_*）。 */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.infra.persistence.billing.PgBillingRbacSchemaInitializer
    pgBillingRbacSchemaInitializer(DataSource observabilityDataSource) {
        return new com.company.agentgateway.infra.persistence.billing.PgBillingRbacSchemaInitializer(
                observabilityDataSource);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.billing.BillingPort.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.billing.BillingPort pgBillingRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.billing.PgBillingRepository(
                observabilityJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.billing.BudgetRepository.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.billing.BudgetRepository pgBudgetRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.billing.PgBudgetRepository(
                observabilityJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.quota.QuotaPort.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.quota.QuotaPort pgQuotaRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.quota.PgQuotaRepository(
                observabilityJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RoleRepository.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.iam.RoleRepository pgRoleRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.rbac.PgRoleRepository(
                observabilityJdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RoleBindingRepository.class)
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.iam.RoleBindingRepository pgRoleBindingRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.rbac.PgRoleBindingRepository(
                observabilityJdbcTemplate);
    }

    // ================= PG/TimescaleDB 可观测性存储（spec 2026-08-19） =================

    /**
     * R16 #4:DataSource 改为 {@code @ConditionalOnMissingBean},让 {@link HikariPgConfig} 优先。
     * HikariPgConfig 在 observability.storage.enabled=true 时提供 HikariDataSource;
     * 此处降级为 DriverManagerDataSource(测试场景)。
     */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource observabilityDataSource(
            @Value("${observability.storage.jdbc-url}") String jdbcUrl,
            @Value("${observability.storage.username:agentgateway}") String username,
            @Value("${observability.storage.password:agentgateway}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public JdbcTemplate observabilityJdbcTemplate(DataSource observabilityDataSource) {
        return new JdbcTemplate(observabilityDataSource);
    }

    /** ObjectMapper 兜底:json 序列化仅用于 jsonb 列,无需 Boot 定制版。 */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper observabilityObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public PgSchemaInitializer pgSchemaInitializer(DataSource observabilityDataSource) {
        return new PgSchemaInitializer(observabilityDataSource);
    }

    // 返回具体类型:bean 定义类型需同时覆盖 SpanQueryRepository(查询注入)与 SpanWriter
    // (observability 的 @ConditionalOnBean(SpanWriter) 按声明类型匹配)
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public PgSpanStore pgSpanStore(JdbcTemplate observabilityJdbcTemplate, ObjectMapper objectMapper) {
        return new PgSpanStore(observabilityJdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public PgMetricsStore pgMetricsStore(JdbcTemplate observabilityJdbcTemplate, ObjectMapper objectMapper) {
        return new PgMetricsStore(observabilityJdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public AlertStore pgAlertStore(JdbcTemplate observabilityJdbcTemplate, ObjectMapper objectMapper) {
        return new PgAlertStore(observabilityJdbcTemplate, objectMapper);
    }

    /** WorkflowRepository(C1 §3.4 P1):PG 模式下用 PgWorkflowRepository,否则降级 InMemory。 */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    @org.springframework.context.annotation.Primary
    public WorkflowRepository pgWorkflowRepository(JdbcTemplate observabilityJdbcTemplate, ObjectMapper objectMapper) {
        return new PgWorkflowRepository(observabilityJdbcTemplate, objectMapper);
    }

    /** WorkflowDefinitionRepository(C1 §8 扩展):PG 模式下用 PgWorkflowDefinitionRepository。 */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    @org.springframework.context.annotation.Primary
    public WorkflowDefinitionRepository pgWorkflowDefinitionRepository(JdbcTemplate observabilityJdbcTemplate) {
        return new PgWorkflowDefinitionRepository(observabilityJdbcTemplate);
    }

    // ================= Replay payload 持久化(Sprint 2 P0 + Round 8 修复) =================

    /** PayloadCipher 密钥(Sprint 2 P0):从 application properties 读 raw key。 */
    @Bean
    @ConditionalOnProperty(name = "gateway.replay.payload-key-ref")
    public PayloadCipher payloadCipher(@Value("${gateway.replay.payload-key-ref}") String keyRef) {
        // 这里简化:直接用 keyRef 字符串派生。生产应从 secret store 取(Sprint 2 P3 待办)。
        return new PayloadCipher(keyRef.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** PayloadCapturePort PG 实现(Sprint 2 P0):observability.storage.enabled=true 时启用。 */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public PayloadCapturePort pgPayloadStore(JdbcTemplate observabilityJdbcTemplate,
                                              PayloadCipher cipher) {
        return new PgPayloadStore(observabilityJdbcTemplate.getDataSource(), cipher);
    }

    /** PayloadCapturePort InMemory fallback(始终存在,保证 controllers 装配不失败)。 */
    @Bean
    @ConditionalOnMissingBean(PayloadCapturePort.class)
    public PayloadCapturePort inMemoryPayloadStore() {
        return new NoOpPayloadStore();
    }

    /** PayloadCaptureHelper(由 controllers / orchestrator 注入,做入口 capture 委托)。 */
    @Bean
    @ConditionalOnMissingBean(PayloadCaptureHelper.class)
    public PayloadCaptureHelper payloadCaptureHelper(PayloadCapturePort port) {
        return new PayloadCaptureHelper(port);
    }

    /** Replay schema 初始化(observability.storage.enabled=true)。 */
    @Bean
    @ConditionalOnProperty(name = "observability.storage.enabled", havingValue = "true")
    public ReplaySchemaInitializer replaySchemaInitializer(DataSource observabilityDataSource) {
        return new ReplaySchemaInitializer(observabilityDataSource);
    }

    // ================= NoOp fallback for SpanQueryRepository(Round 9 导出) =================

    /**
      SpanQueryRepository + SpanWriter fallback — observability.storage.enabled=false 时
      装配,保证 ExportController / AdminReplayController 装配不失败(Round 9 触发,
      与 Round 8 PayloadCapture fallback 同模式)。
     */
    @Bean
    @ConditionalOnMissingBean(SpanQueryRepository.class)
    public SpanQueryRepository noOpSpanQueryRepository() {
        return new NoOpSpanStore();
    }

    @Bean
    @ConditionalOnMissingBean(SpanWriter.class)
    public SpanWriter noOpSpanWriter() {
        return new NoOpSpanWriter();
    }

    // ================= Feedback 标注存储（Round 11） =================

    /**
     * FeedbackRepository 默认 P0 实现（Round 11 §feedback-annotation §3.2）。
     * 始终存在,保证 controllers 装配不失败。
     * R12 替换为 PgFeedbackRepository + {@code observability.storage.enabled=true} 条件装配。
     */
    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.feedback.FeedbackRepository.class)
    public com.company.agentgateway.domain.feedback.FeedbackRepository inMemoryFeedbackRepository() {
        return new InMemoryFeedbackRepository();
    }

    // ================= Admin User / Team（Round 12 §multi-admin） =================

    /**
     * AdminUserRepository 默认 P0 实现。始终存在。
     * R13 替换为 PgAdminUserRepository + bcrypt。
     */
    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.admin.AdminUserRepository.class)
    public com.company.agentgateway.domain.iam.admin.AdminUserRepository inMemoryAdminUserRepository() {
        return new com.company.agentgateway.infra.persistence.admin.InMemoryAdminUserRepository();
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.admin.TeamRepository.class)
    public com.company.agentgateway.domain.iam.admin.TeamRepository inMemoryTeamRepository() {
        return new com.company.agentgateway.infra.persistence.admin.InMemoryTeamRepository();
    }

    // ================= Pg 实现（Round 15 #2 §pg-persistence） =================

    /**
     * AdminUser Pg 实现:observability.storage.enabled=true 时启用。
     * 需 Spring JdbcTemplate(由本类的 observabilityJdbcTemplate 提供)。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.iam.admin.AdminUserRepository pgAdminUserRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.admin.PgAdminUserRepository(observabilityJdbcTemplate);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "observability.storage.enabled", havingValue = "true")
    public com.company.agentgateway.domain.feedback.FeedbackRepository pgFeedbackRepository(
            JdbcTemplate observabilityJdbcTemplate) {
        return new com.company.agentgateway.infra.persistence.feedback.PgFeedbackRepository(observabilityJdbcTemplate);
    }

    // ================= Prompt Template / Version（Round 12 §prompt-version） =================

    /**
     * SharedPromptStore 共享底层存储 + 两个 Repo 共用,保证级联删除一致。
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public com.company.agentgateway.infra.persistence.prompt.SharedPromptStore sharedPromptStore() {
        return new com.company.agentgateway.infra.persistence.prompt.SharedPromptStore();
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.prompt.PromptTemplateRepository.class)
    public com.company.agentgateway.domain.prompt.PromptTemplateRepository inMemoryPromptTemplateRepository(
            com.company.agentgateway.infra.persistence.prompt.SharedPromptStore store) {
        return new com.company.agentgateway.infra.persistence.prompt.InMemoryPromptTemplateRepository(store);
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.prompt.PromptVersionRepository.class)
    public com.company.agentgateway.domain.prompt.PromptVersionRepository inMemoryPromptVersionRepository(
            com.company.agentgateway.infra.persistence.prompt.SharedPromptStore store) {
        return new com.company.agentgateway.infra.persistence.prompt.InMemoryPromptVersionRepository(store);
    }

    // ================= Round 15 #1 plugins =================

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.plugin.PluginRegistry.class)
    public com.company.agentgateway.domain.plugin.PluginRegistry inMemoryPluginRegistry() {
        return new com.company.agentgateway.infra.persistence.plugin.InMemoryPluginRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(name = "pluginServiceLoaderPlugins")
    public com.company.agentgateway.domain.plugin.Plugin[] pluginServiceLoaderPlugins() {
        java.util.List<com.company.agentgateway.domain.plugin.Plugin> found = new java.util.ArrayList<>();
        for (com.company.agentgateway.domain.plugin.Plugin p : java.util.ServiceLoader
                .load(com.company.agentgateway.domain.plugin.Plugin.class)) {
            found.add(p);
        }
        return found.toArray(new com.company.agentgateway.domain.plugin.Plugin[0]);
    }
}
