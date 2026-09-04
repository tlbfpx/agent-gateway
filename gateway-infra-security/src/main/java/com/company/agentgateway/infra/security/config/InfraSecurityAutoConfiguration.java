package com.company.agentgateway.infra.security.config;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.Authenticator;
import com.company.agentgateway.domain.iam.AuthorizationService;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.company.agentgateway.infra.security.ApiKeyAuthenticator;
import com.company.agentgateway.infra.security.ApiKeyStore;
import com.company.agentgateway.infra.security.AuthorizationServiceImpl;
import com.company.agentgateway.infra.security.JsonFileApiKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * infra-security 运行期装配。
 *
 * <p>ApiKeyStore 默认 InMemory + <b>配置持久化</b>：启动时从 {@code gateway.api-keys.*} 加载，
 * 重启不丢（此前 InMemory 重启即清空导致前端 Key 全部失效）。
 * 动态签发的 Key（admin API）仅在内存——生产期替换为 DB/Redis store bean。
 *
 * <p>配置格式（application.yml / 环境变量 / Nacos 皆可）：
 * <pre>
 * gateway:
 *   api-keys:
 *     - key: sk-demo-key
 *       tenant: t1
 *       user: demo
 *       agentGrants: [echo-agent]
 *       allowedModels: [minimax-abab6.5s-chat]
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class InfraSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InfraSecurityAutoConfiguration.class);

    /**
     * 可写 JSON 文件存储：admin 签发/吊销持久化到 data/api-keys.json（重启不丢）。
     * 静态 gateway.api-keys.* 配置作为种子合并注册（不落盘重复）。
     */
    @Bean
    @ConditionalOnMissingBean(ApiKeyStore.class)
    public ApiKeyStore apiKeyStore(
            GatewaySecurityProperties props,
            @org.springframework.beans.factory.annotation.Value("${gateway.security.key-file:data/api-keys.json}") String keyFile) {
        JsonFileApiKeyStore store = new JsonFileApiKeyStore(java.nio.file.Path.of(keyFile));
        int seeded = 0;
        for (Map<String, Object> cfg : props.getApiKeys()) {
            try {
                String key = str(cfg, "key");
                String tenant = str(cfg, "tenant");
                String user = str(cfg, "user");
                if (key == null || tenant == null || user == null) {
                    log.warn("Skip api-key config without key/tenant/user");
                    continue;
                }
                if (store.findByKey(key).isPresent()) continue; // 文件已有，不覆盖
                var tenants = new java.util.LinkedHashSet<TenantId>();
                tenants.add(new TenantId(tenant));
                tenants.addAll(stringList(cfg.get("tenants")).stream()
                        .map(TenantId::new).toList());
                store.register(key, new ApiKeyStore.ApiKeyBinding(
                        new TenantId(tenant),
                        new UserId(user),
                        grants(cfg.get("agentGrants")),
                        models(cfg.get("allowedModels")),
                        false, tenants));
                seeded++;
            } catch (Exception e) {
                log.warn("Failed to seed api-key: {}", e.getMessage());
            }
        }
        log.info("ApiKeyStore[{}]: {} file key(s), {} seeded from config", keyFile, store.listKeys().size(), seeded);
        return store;
    }

    @Bean
    public Authenticator authenticator(ApiKeyStore store) {
        return new com.company.agentgateway.infra.security.TenantAwareAuthenticator(store);
    }

    // ====== D1 RBAC：3 个 Port 默认 Bean（InMemory / 占位，spec §GW-RBAC-002/004）======

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RoleRepository.class)
    public com.company.agentgateway.domain.iam.RoleRepository roleRepository() {
        return new com.company.agentgateway.infra.security.rbac.InMemoryRoleRepository();
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RoleBindingRepository.class)
    public com.company.agentgateway.domain.iam.RoleBindingRepository roleBindingRepository() {
        return new com.company.agentgateway.infra.security.rbac.InMemoryRoleBindingRepository();
    }

    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RbacChangePublisher.class)
    public com.company.agentgateway.domain.iam.RbacChangePublisher rbacChangePublisher() {
        return new com.company.agentgateway.infra.security.rbac.NacosRbacChangePublisher();
    }

    /**
     * RoleQueryService（domain 无状态服务，spec §GW-RBAC-011）。
     * D2 顺手修复：D1 遗漏注册导致 bootstrap 上下文 adminRbacPreviewController 装配失败。
     */
    @Bean
    @ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RoleQueryService.class)
    public com.company.agentgateway.domain.iam.RoleQueryService roleQueryService() {
        return new com.company.agentgateway.domain.iam.RoleQueryService();
    }

    /** 升级模式（spec §GW-RBAC-005）：注入 2 个仓储，决策并集。 */
    @Bean
    public AuthorizationService authorizationService(
            com.company.agentgateway.domain.iam.RoleRepository roleRepository,
            com.company.agentgateway.domain.iam.RoleBindingRepository roleBindingRepository) {
        return new AuthorizationServiceImpl(roleRepository, roleBindingRepository);
    }

    /** 限流（spec §8.3 五维度，一期 InMemory 固定窗口；0 = 不限）。 */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(com.company.agentgateway.domain.iam.RateLimiter.class)
    public com.company.agentgateway.domain.iam.RateLimiter rateLimiter(
            @org.springframework.beans.factory.annotation.Value("${gateway.rate-limit.tenant-qps:0}") long tenantQps,
            @org.springframework.beans.factory.annotation.Value("${gateway.rate-limit.user-qps:0}") long userQps,
            @org.springframework.beans.factory.annotation.Value("${gateway.rate-limit.api-key-qps:0}") long apiKeyQps,
            @org.springframework.beans.factory.annotation.Value("${gateway.rate-limit.agent-concurrency:0}") int agentConcurrency,
            @org.springframework.beans.factory.annotation.Value("${gateway.rate-limit.tenant-daily-tokens:0}") long tenantDailyTokens) {
        log.info("RateLimiter: tenantQps={} userQps={} apiKeyQps={} agentConcurrency={} tenantDailyTokens={}",
                tenantQps, userQps, apiKeyQps, agentConcurrency, tenantDailyTokens);
        return new com.company.agentgateway.infra.security.InMemoryRateLimiter(
                tenantQps, userQps, apiKeyQps, agentConcurrency, tenantDailyTokens);
    }

    private static String str(Map<String, Object> cfg, String k) {
        Object v = cfg.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Set<AgentGrant> grants(Object raw) {
        return stringList(raw).stream()
                .map(a -> new AgentGrant(a, Set.of()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<ModelId> models(Object raw) {
        return stringList(raw).stream()
                .map(ModelId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
