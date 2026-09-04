package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;

import java.util.Optional;
import java.util.Set;

/**
 * API Key 存储（spec §6.1：key 绑定 tenant/user/grants/allowedModels）。
 *
 * <p>一期：InMemory（默认，配置/测试用）；二期接 DB/Redis（条件装配，与 session-store 模式一致）。
 * 管理 REST（签发/吊销 CRUD）归 add-admin-console change；本接口只提供认证查询。
 */
public interface ApiKeyStore {

    /** 按 key 查找绑定的身份信息。不存在/吊销/已过期返回 empty。 */
    Optional<ApiKeyBinding> findByKey(String apiKey);

    /** 管理列表（GET /v1/admin/api-keys）：key → binding 全量快照。默认空（实现按需覆写）。 */
    default java.util.List<java.util.Map.Entry<String, ApiKeyBinding>> entries() {
        return java.util.List.of();
    }

    /** API Key 绑定的身份信息（认证后用于构造 AuthPrincipal）。 */
    /** tenants = 授权租户列表（含主租户 tenant；spec §6.2 二期多租户切换）。 */
    record ApiKeyBinding(TenantId tenant, UserId user,
                         Set<AgentGrant> agentGrants, Set<ModelId> allowedModels,
                         boolean revoked, java.util.Set<TenantId> tenants,
                         java.time.Instant expiresAt) {
        /** 兼容旧构造（单租户 = 仅主租户，永不过期）。 */
        public ApiKeyBinding(TenantId tenant, UserId user,
                     Set<AgentGrant> agentGrants, Set<ModelId> allowedModels, boolean revoked) {
            this(tenant, user, agentGrants, allowedModels, revoked, java.util.Set.of(tenant), null);
        }
        /** 兼容旧构造（单租户 = 仅主租户，永不过期）。 */
        public ApiKeyBinding(TenantId tenant, UserId user,
                     Set<AgentGrant> agentGrants, Set<ModelId> allowedModels, boolean revoked,
                     java.util.Set<TenantId> tenants) {
            this(tenant, user, agentGrants, allowedModels, revoked, tenants, null);
        }
        boolean allowsTenant(TenantId target) { return tenants.contains(target); }

        /** 是否已过期（expiresAt 为 null 表示永不过期）。 */
        public boolean isExpired(java.time.Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }
    }
}
