package com.company.agentgateway.domain.billing;

import com.company.agentgateway.domain.shared.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Virtual Key 仓储端口（spec §21.7）。
 *
 * <p>由应用层 / 基础设施层提供实现。一期在 {@code StripeStubAdapter}
 * 内嵌 ConcurrentHashMap-based 默认实现，避免新增 infra 文件；
 * 二期 JPA 实现同接口 @Primary 覆盖。
 */
public interface VirtualKeyRepository {

    Optional<VirtualKey> findById(String vkId);

    List<VirtualKey> findByTenant(TenantId tenant);

    void save(VirtualKey vk);

    /** 标记吊销（status=REVOKED），保留余额审计；幂等：重复调用不抛异常。 */
    void revoke(String vkId);
}