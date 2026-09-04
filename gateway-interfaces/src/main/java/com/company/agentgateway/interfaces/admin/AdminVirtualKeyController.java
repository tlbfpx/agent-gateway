package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.TopUpPort;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.billing.VirtualKey;
import com.company.agentgateway.domain.billing.VirtualKeyRepository;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Virtual Key 管理端点（spec §21.7）。
 *
 * <p>端点：
 * <ul>
 *   <li>POST   /v1/admin/virtual-keys              — 创建</li>
 *   <li>GET    /v1/admin/virtual-keys              — 列表（按 tenant）</li>
 *   <li>GET    /v1/admin/virtual-keys/{id}         — 详情（404 不存在）</li>
 *   <li>DELETE /v1/admin/virtual-keys/{id}         — 吊销</li>
 *   <li>POST   /v1/admin/virtual-keys/{id}/topup   — 充值开单（返回 Stripe Checkout URL）</li>
 *   <li>GET    /v1/admin/virtual-keys/{id}/usage   — 该 VK 所属 tenant 的近期用量</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/virtual-keys")
public class AdminVirtualKeyController {

    private final TopUpPort topUpPort;
    private final VirtualKeyRepository vkRepo;
    private final BillingPort billingPort;

    public AdminVirtualKeyController(TopUpPort topUpPort,
                                     VirtualKeyRepository vkRepo,
                                     BillingPort billingPort) {
        this.topUpPort = topUpPort;
        this.vkRepo = vkRepo;
        this.billingPort = billingPort;
    }

    /** 创建 Virtual Key。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateVkRequest req) {
        if (req == null || isBlank(req.owner()) || isBlank(req.tenant())
                || isBlank(req.label())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "owner, tenant, label are required");
        }
        BigDecimal quota = req.monthlyQuotaCny() != null ? req.monthlyQuotaCny() : BigDecimal.ZERO;
        String vkId = "vk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        VirtualKey vk = new VirtualKey(
                vkId, req.owner(), new TenantId(req.tenant()), req.label(),
                quota, BigDecimal.ZERO, VirtualKey.Status.ACTIVE, Instant.now());
        vkRepo.save(vk);
        return toView(vk);
    }

    /** 按 tenant 列表。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "tenant", required = false) String tenantId) {
        TenantId tenant = resolveTenant(tenantId);
        return vkRepo.findByTenant(tenant).stream()
                .map(this::toView)
                .toList();
    }

    /** 详情（404 不存在）。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") String vkId) {
        return vkRepo.findById(vkId)
                .map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "virtual key not found: " + vkId));
    }

    /** 吊销。 */
    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> revoke(@PathVariable("id") String vkId) {
        vkRepo.revoke(vkId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    /** 充值开单（Stripe Checkout Session）。 */
    @PostMapping("/{id}/topup")
    public Map<String, Object> topup(@PathVariable("id") String vkId,
                                     @RequestBody TopUpRequest req) {
        if (req == null || req.amountCny() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCny required");
        }
        TopUpPort.CheckoutSession session = topUpPort.createCheckoutSession(vkId, req.amountCny());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("checkoutUrl", session.checkoutUrl());
        view.put("sessionId", session.sessionId());
        view.put("amountCny", session.amountCny());
        view.put("vkId", vkId);
        return view;
    }

    /** 该 VK 所属 tenant 的近期用量。 */
    @GetMapping("/{id}/usage")
    public List<UsageRecord> usage(@PathVariable("id") String vkId,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to) {
        VirtualKey vk = vkRepo.findById(vkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "virtual key not found: " + vkId));
        Instant fromI = from != null ? from : Instant.now().minusSeconds(30L * 24 * 3600);
        Instant toI = to != null ? to : Instant.now();
        return billingPort.queryUsage(new UsageQuery(vk.tenant(), fromI, toI, null, null));
    }

    /** 创建请求 DTO。 */
    public record CreateVkRequest(String owner, String tenant, String label, BigDecimal monthlyQuotaCny) {}

    /** 充值请求 DTO。 */
    public record TopUpRequest(BigDecimal amountCny) {}

    // ---- helpers ----

    private Map<String, Object> toView(VirtualKey vk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vkId", mask(vk.vkId()));
        m.put("owner", vk.owner());
        m.put("tenant", vk.tenant().value());
        m.put("label", vk.label());
        m.put("monthlyQuotaCny", vk.monthlyQuotaCny());
        m.put("balanceCny", vk.balanceCny());
        m.put("status", vk.status().name());
        m.put("createdAt", vk.createdAt().toString());
        return m;
    }

    /** vkId 脱敏：保留前 3 后 4，中间 ****。 */
    private static String mask(String id) {
        if (id == null) return null;
        if (id.length() <= 7) return id;
        return id.substring(0, 3) + "****" + id.substring(id.length() - 4);
    }

    private static TenantId resolveTenant(String tenantId) {
        return new TenantId(tenantId == null || tenantId.isBlank() ? "primary" : tenantId);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    // Suppress unused-imports warning for Optional import (kept for IDE helper visibility)
    @SuppressWarnings("unused")
    private static <T> Optional<T> unused() { return Optional.empty(); }
}