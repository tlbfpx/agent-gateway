package com.company.agentgateway.application.billing;

import com.company.agentgateway.domain.billing.StripeCheckoutException;
import com.company.agentgateway.domain.billing.TopUpPort;
import com.company.agentgateway.domain.billing.VirtualKey;
import com.company.agentgateway.domain.billing.VirtualKeyRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stripe 充值适配器（Stub 实现，spec §21.7）。
 *
 * <p>职责：
 * <ul>
 *   <li>开单：生成 Stripe-style Checkout Session URL + sessionId（进程内 pending map）</li>
 *   <li>回调：消费 {@code checkout.session.completed} 与
 *       {@code checkout.session.async_payment_succeeded} → 累加 balanceCny → 清 pending</li>
 * </ul>
 *
 * <p>不引入真实 Stripe SDK；URL/Session ID 用本地 UUID 模拟，方便端到端测试。
 *
 * <p>进程内嵌 {@link InMemoryVirtualKeyRepository}（避免新增 infra 文件）：
 * bootstrap 显式 @Bean 注册，domain 包不被 Spring 扫描。
 */
public class StripeStubAdapter implements TopUpPort, VirtualKeyRepository {

    private static final Logger log = LoggerFactory.getLogger(StripeStubAdapter.class);

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded");

    private final InMemoryVirtualKeyRepository repository = new InMemoryVirtualKeyRepository();

    /** pending session → (vkId, amount)。 */
    private final Map<String, PendingSession> pending = new ConcurrentHashMap<>();

    /** 测试/管理用：暴露仓储供 controllers 注入。 */
    public VirtualKeyRepository repository() {
        return repository;
    }

    // ---- VirtualKeyRepository 委托（让 Spring 单 bean 同时是 TopUpPort + VirtualKeyRepository） ----

    @Override
    public java.util.Optional<VirtualKey> findById(String vkId) {
        return repository.findById(vkId);
    }

    @Override
    public java.util.List<VirtualKey> findByTenant(com.company.agentgateway.domain.shared.TenantId tenant) {
        return repository.findByTenant(tenant);
    }

    @Override
    public void save(VirtualKey vk) {
        repository.save(vk);
    }

    @Override
    public void revoke(String vkId) {
        repository.revoke(vkId);
    }

    @Override
    public CheckoutSession createCheckoutSession(String vkId, BigDecimal amountCny) {
        if (vkId == null || vkId.isBlank()) {
            throw StripeCheckoutException.validation("vkId must not be blank");
        }
        if (amountCny == null || amountCny.signum() <= 0) {
            throw StripeCheckoutException.validation("amount must be > 0");
        }
        VirtualKey existing = repository.findById(vkId)
                .orElseThrow(() -> StripeCheckoutException.notFound("virtual key not found: " + vkId));
        if (existing.status() == VirtualKey.Status.REVOKED) {
            throw StripeCheckoutException.validation("virtual key revoked: " + vkId);
        }
        String sessionId = "cs_test_" + UUID.randomUUID().toString().replace("-", "");
        String checkoutUrl = "https://checkout.stripe.com/c/test_" + vkId;
        pending.put(sessionId, new PendingSession(vkId, amountCny));
        return new CheckoutSession(checkoutUrl, sessionId, amountCny);
    }

    @Override
    public void handleStripeEvent(StripeEvent event) {
        if (event == null) {
            log.warn("Stripe event is null, dropping");
            return;
        }
        if (!SUPPORTED_EVENTS.contains(event.type())) {
            log.debug("Stripe event type ignored: {}", event.type());
            return;
        }
        PendingSession p = pending.remove(event.sessionId());
        if (p == null) {
            log.warn("Stripe event references unknown session: {}", event.sessionId());
            return;
        }
        VirtualKey vk = repository.findById(p.vkId)
                .orElseThrow(() -> StripeCheckoutException.notFound("virtual key missing: " + p.vkId));
        if (vk.status() == VirtualKey.Status.REVOKED) {
            log.warn("Top-up ignored: virtual key revoked: {}", p.vkId);
            return;
        }
        BigDecimal newBalance = vk.balanceCny().add(event.amountCny());
        VirtualKey updated = new VirtualKey(
                vk.vkId(), vk.owner(), vk.tenant(), vk.label(),
                vk.monthlyQuotaCny(), newBalance, vk.status(), vk.createdAt());
        repository.save(updated);
        log.info("Stripe top-up credited: vk={} +{} -> balance={}",
                p.vkId, event.amountCny(), newBalance);
    }

    private record PendingSession(String vkId, BigDecimal amountCny) {}

    /** 进程内 VirtualKey 仓储（ConcurrentHashMap-based）。 */
    public static final class InMemoryVirtualKeyRepository implements VirtualKeyRepository {

        private final Map<String, VirtualKey> store = new ConcurrentHashMap<>();

        @Override
        public Optional<VirtualKey> findById(String vkId) {
            if (vkId == null) return Optional.empty();
            return Optional.ofNullable(store.get(vkId));
        }

        @Override
        public List<VirtualKey> findByTenant(TenantId tenant) {
            if (tenant == null) return List.of();
            return store.values().stream()
                    .filter(v -> v.tenant().equals(tenant))
                    .toList();
        }

        @Override
        public void save(VirtualKey vk) {
            if (vk == null) throw new IllegalArgumentException("vk must not be null");
            store.put(vk.vkId(), vk);
        }

        @Override
        public void revoke(String vkId) {
            VirtualKey existing = store.get(vkId);
            if (existing == null) return; // 幂等：未注册不抛
            if (existing.status() == VirtualKey.Status.REVOKED) return;
            store.put(vkId, new VirtualKey(
                    existing.vkId(), existing.owner(), existing.tenant(), existing.label(),
                    existing.monthlyQuotaCny(), existing.balanceCny(),
                    VirtualKey.Status.REVOKED, existing.createdAt()));
        }
    }
}