package com.company.agentgateway.interfaces.demo;

import com.company.agentgateway.application.admin.auth.AdminAuthService;
import com.company.agentgateway.application.admin.auth.PasswordHasher;
import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.company.agentgateway.infra.security.ApiKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Demo 模式核心服务（spec 2026-09-04 §demo-mode §4）。
 *
 * <p>{@link #bootstrap()} 一次调用产出 demo 三件套：
 * <ol>
 *   <li>{@code tenantId} — {@code demo-{UUID 前 8 位}}，独立数据隔离</li>
 *   <li>{@code apiKey} — {@code sk-demo-{32 位随机}}，24h 后自动过期</li>
 *   <li>{@code adminToken} — bcrypt 登录会话（X-Admin-Token），用于管理端</li>
 * </ol>
 *
 * <p>apiKey 走 {@link ApiKeyStore#register}（带 expiresAt），到时 {@link #cleanup()}
 * 调用 {@link ApiKeyStore#revoke} 移除；admin session 由 AdminAuthService 内部 TTL 兜底。
 */
@Service
public class DemoService {

    /** 累计 demo bootstrap 次数（spec §business-stats round 39）。重启清零。 */
    private final java.util.concurrent.atomic.AtomicLong bootstrapCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    public long getBootstrapCount() {
        return bootstrapCount.get();
    }

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final DemoConfig config;
    private final ApiKeyStore apiKeyStore;
    private final AdminUserRepository adminUserRepo;
    private final AdminAuthService adminAuthService;

    public DemoService(DemoConfig config,
                       ApiKeyStore apiKeyStore,
                       AdminUserRepository adminUserRepo,
                       AdminAuthService adminAuthService) {
        this.config = config;
        this.apiKeyStore = apiKeyStore;
        this.adminUserRepo = adminUserRepo;
        this.adminAuthService = adminAuthService;
    }

    /** 当前是否启用 demo 模式。供 controller 静态检查。 */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 创建一个独立的 demo 租户：tenant + apiKey + admin user + admin token。
     * 多次调用产生多个互不干扰的 demo 租户。
     */
    public DemoSession bootstrap() {
        if (!config.isEnabled()) {
            throw new IllegalStateException("demo mode is disabled");
        }
        String tenantId = "demo-" + UUID.randomUUID().toString().substring(0, 8);
        String apiKey = generateApiKey();
        String adminEmail = tenantId + "@demo.local";
        String adminPassword = generatePassword();

        Instant expiresAt = Instant.now().plus(config.getTtl());

        // 1) API Key（绑定 demo 租户 + echo-agent + 演示模型；带 expiresAt）
        Set<ModelId> allowedModels = new LinkedHashSet<>();
        allowedModels.add(new ModelId("minimax-abab6.5s-chat"));
        Set<AgentGrant> grants = Set.of(new AgentGrant("echo-agent", Set.of()));
        apiKeyStore.register(apiKey, new ApiKeyStore.ApiKeyBinding(
                new TenantId(tenantId),
                new UserId(tenantId),
                grants,
                allowedModels,
                false,
                Set.of(new TenantId(tenantId)),
                expiresAt));
        log.info("demo.bootstrap tenant={} apiKey=sk-****{} expiresAt={}",
                tenantId, apiKey.substring(apiKey.length() - 4), expiresAt);

        // 2) Admin User（bcrypt 密码；OWNER 角色走管理端全权）
        AdminUser user = AdminUser.create(
                adminEmail,
                "Demo Admin",
                AdminRole.OWNER,
                tenantId,
                PasswordHasher.hash(adminPassword));
        adminUserRepo.save(user);

        // 3) Admin Token（直接调 login 拿 v1.<id>.<random> session token）
        AdminAuthService.LoginResult loginResult;
        try {
            loginResult = adminAuthService.login(tenantId, adminEmail, adminPassword);
        } catch (Exception e) {
            // 兜底：bcrypt 正常不会失败；若失败则回滚 key 防止孤儿数据
            apiKeyStore.revoke(apiKey);
            throw new IllegalStateException("admin login bootstrap failed: " + e.getMessage(), e);
        }

        bootstrapCount.incrementAndGet();
        return new DemoSession(
                tenantId,
                apiKey,
                loginResult.token(),
                adminEmail,
                expiresAt);
    }

    /**
     * 清理过期 demo apiKey。
     * 调用 {@link ApiKeyStore#revoke}（从 in-memory + 持久化文件移除）；
     * AdminAuthService 内的 session 由其自身的 TTL 兜底，无需此处处理。
     */
    public int cleanup() {
        if (!config.isEnabled()) return 0;
        Instant now = Instant.now();
        int removed = 0;
        for (var entry : apiKeyStore.entries()) {
            String key = entry.getKey();
            ApiKeyStore.ApiKeyBinding b = entry.getValue();
            if (b.tenant().value().startsWith("demo-")
                    && b.expiresAt() != null
                    && now.isAfter(b.expiresAt())) {
                apiKeyStore.revoke(key);
                removed++;
                log.info("demo.cleanup.removed tenant={} apiKey=sk-****{}",
                        b.tenant().value(), key.substring(key.length() - 4));
            }
        }
        return removed;
    }

    private static String generateApiKey() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return "sk-demo-" + java.util.HexFormat.of().formatHex(buf);
    }

    private static String generatePassword() {
        // 24 hex chars = 12 bytes = 96 bit entropy;demo 一次性，无需记忆
        byte[] buf = new byte[12];
        RNG.nextBytes(buf);
        return java.util.HexFormat.of().formatHex(buf);
    }
}