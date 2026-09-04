package com.company.agentgateway.interfaces.admin;

import com.company.agentgateway.domain.audit.AuditRepository;
import com.company.agentgateway.domain.billing.BillingPort;
import com.company.agentgateway.domain.billing.UsageQuery;
import com.company.agentgateway.domain.billing.UsageRecord;
import com.company.agentgateway.domain.shared.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营指标聚合（管理端）。
 *
 * <p>数据源为 AuditRepository（append-only 审计流），按前端 usage.ts 预留的
 * 契约输出——前端 fetchLive* 命中后 Dashboard / 成本中心自动从"派生降级"
 * 切换为"● 实时"标签。
 *
 * <p>端点（均需 X-API-Key）：
 * <ul>
 *   <li>GET /v1/admin/metrics/overview          — 24h 调用量 / 活跃 Key / 错误率</li>
 *   <li>GET /v1/admin/metrics/usage?range=24h   — 按小时分桶的时间序列</li>
 *   <li>GET /v1/admin/metrics/top?by=model|tenant|key — Top N 切片</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/metrics")
public class AdminMetricsController {

    private final AuditRepository auditRepository;

    /** D2 GW-QUOTA-009：计费真实数据源（nullable — 未装配时退化为估算口径）。 */
    private final BillingPort billingPort;

    public AdminMetricsController(AuditRepository auditRepository) {
        this(auditRepository, null);
    }

    @Autowired
    public AdminMetricsController(AuditRepository auditRepository, BillingPort billingPort) {
        this.auditRepository = auditRepository;
        this.billingPort = billingPort;
    }

    /** 24h 总览：调用量 / 错误率（活跃 Key 由前端 keys API 叠加）。 */
    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        String tenant = resolveTenant(tenantId);
        List<AuditRepository.AuditLog> recent = auditRepository.query(
                new TenantId(tenant), null, Instant.now().minus(Duration.ofHours(24)), null, 10_000);

        long total = recent.size();
        long failed = recent.stream().filter(l -> l.result() == AuditRepository.AuditLog.Result.FAILURE).count();
        return Map.of(
                "requests24h", total,
                "errorRate", total == 0 ? 0.0 : (double) failed / total,
                "p95LatencyMs", 0);
    }

    /** 时间序列：按小时分桶（24h → 24 桶，7d → 天级）。 */
    @GetMapping("/usage")
    public List<Map<String, Object>> usage(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam(defaultValue = "24h") String range) {
        String tenant = resolveTenant(tenantId);
        Duration window = parseRange(range);
        Instant from = Instant.now().minus(window);
        List<AuditRepository.AuditLog> recent = auditRepository.query(
                new TenantId(tenant), null, from, null, 10_000);

        // 桶大小：≤24h 用小时桶；更长窗口用天桶
        Duration bucket = window.toHours() <= 24 ? Duration.ofHours(1) : Duration.ofDays(1);
        Map<String, long[]> buckets = new LinkedHashMap<>();
        long bucketCount = window.dividedBy(bucket);
        for (long i = bucketCount - 1; i >= 0; i--) {
            Instant t = Instant.now().minus(bucket.multipliedBy(i));
            buckets.put(label(t, bucket), new long[]{0, 0});
        }
        for (AuditRepository.AuditLog l : recent) {
            String key = label(l.timestamp(), bucket);
            long[] b = buckets.get(key);
            if (b == null) continue; // 超出预建桶（时钟偏移）忽略
            b[0]++;
            if (l.result() == AuditRepository.AuditLog.Result.FAILURE) b[1]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.forEach((k, v) -> out.add(Map.of("t", k, "n", v[0], "err", v[1])));
        return out;
    }

    /** Top 切片：by=model（resourceId）/ by=tenant（actor 域后缀）/ by=key（actor）。 */
    @GetMapping("/top")
    public List<Map<String, Object>> top(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam String by,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "10") int limit) {
        String tenant = resolveTenant(tenantId);
        Instant from = Instant.now().minus(parseRange(range));
        List<AuditRepository.AuditLog> recent = auditRepository.query(
                new TenantId(tenant), null, from, null, 10_000);

        Map<String, long[]> agg = new LinkedHashMap<>(); // [calls, errors]
        for (AuditRepository.AuditLog l : recent) {
            String id = switch (by) {
                // 审计 resourceId 为笼统类型（"session" 等）时归入对话桶
                case "model" -> isGenericResource(l.resourceId()) ? "chat (对话调用)" :
                        (l.resourceId() == null ? "unknown" : l.resourceId());
                // actor 无 @ 域时（如 "u"）用请求租户本身
                case "tenant" -> {
                    String d = actorDomain(l.actor());
                    yield "unknown".equals(d) ? tenant : d;
                }
                case "key" -> l.actor() == null ? "unknown" : l.actor();
                default -> "unknown";
            };
            long[] v = agg.computeIfAbsent(id, k -> new long[]{0, 0});
            v[0]++;
            if (l.result() == AuditRepository.AuditLog.Result.FAILURE) v[1]++;
        }
        return agg.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> -v[0])))
                .limit(Math.max(1, limit))
                .map(e -> Map.<String, Object>of(
                        "id", e.getKey(),
                        "name", e.getKey(),
                        "n", e.getValue()[0],
                        "err", e.getValue()[1]))
                .toList();
    }

    /** 笼统资源类型（审计未细化到模型名时的占位值）。 */
    private static boolean isGenericResource(String resourceId) {
        return resourceId == null || resourceId.isBlank()
                || "session".equals(resourceId) || "model".equals(resourceId);
    }

    /**
     * 成本报表（成本中心 live 数据源）。
     *
     * <p>按审计流 4 维度切片（tenant / key / model / day），契约对齐前端
     * usage.ts 的 CostReport。<b>D2 GW-QUOTA-009</b>：token/成本优先取
     * {@code BillingPort.queryUsage} 真实记账（按模型均摊到审计调用次数），
     * 无记账数据时退化为估算口径（1500 token/次 + 内置价格表）。契约不变。
     */
    @GetMapping("/cost")
    public Map<String, Object> cost(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestParam(defaultValue = "24h") String range) {
        String tenant = resolveTenant(tenantId);
        Instant from = Instant.now().minus(parseRange(range));
        List<AuditRepository.AuditLog> recent = auditRepository.query(
                new TenantId(tenant), null, from, null, 10_000);
        Map<String, double[]> usageByModel = queryRealUsageByModel(tenant, from);

        Agg total = new Agg();
        Map<String, Agg> byTenant = new LinkedHashMap<>();
        Map<String, Agg> byKey = new LinkedHashMap<>();
        Map<String, Agg> byModel = new LinkedHashMap<>();
        Map<String, Agg> byDay = new LinkedHashMap<>();

        for (AuditRepository.AuditLog l : recent) {
            String modelId = l.resourceId() == null ? "unknown" : l.resourceId();
            String day = l.timestamp().toString().substring(0, 10);
            boolean failed = l.result() == AuditRepository.AuditLog.Result.FAILURE;
            double[] real = usageByModel.get(modelId);
            long tokens;
            double cost;
            if (real != null && real[0] > 0) {
                // 真实记账口径：按模型总 token / 总次数均摊（spec §21.3 单一数据源）
                tokens = (long) (real[1] / real[0]);
                cost = real[2] / real[0];
            } else {
                // 估算口径降级（审计无 token 字段且计费无记账）
                tokens = 1500L;
                cost = priceOf(modelId, tokens);
            }

            total.add(tokens, failed, cost);
            byTenant.computeIfAbsent(actorDomain(l.actor()), k -> new Agg()).add(tokens, failed, cost);
            byKey.computeIfAbsent(l.actor() == null ? "unknown" : l.actor(), k -> new Agg()).add(tokens, failed, cost);
            byModel.computeIfAbsent(modelId, k -> new Agg()).add(tokens, failed, cost);
            byDay.computeIfAbsent(day, k -> new Agg()).add(tokens, failed, cost);
        }

        return Map.of(
                "total", total.toMap(),
                "byTenant", toRows(byTenant, "tenant"),
                "byKey", toRows(byKey, "key"),
                "byModel", toRows(byModel, "model"),
                "byDay", toRows(byDay, "day"),
                "live", true,
                "range", range);
    }

    /** 查询计费真实记账，按模型聚合 [次数, token 总量, 成本总量]；无 BillingPort 返回空 Map。 */
    private Map<String, double[]> queryRealUsageByModel(String tenant, Instant from) {
        Map<String, double[]> usageByModel = new HashMap<>();
        if (billingPort == null) return usageByModel;
        try {
            List<UsageRecord> records = billingPort.queryUsage(
                    new UsageQuery(new TenantId(tenant), from, Instant.now(), null, null));
            for (UsageRecord r : records) {
                double[] s = usageByModel.computeIfAbsent(r.model().value(), k -> new double[3]);
                s[0] += 1;
                s[1] += r.tokensIn() + r.tokensOut();
                s[2] += r.cost().doubleValue();
            }
        } catch (Exception e) {
            // 计费源故障 → 降级估算口径，不阻断报表（GW-QUOTA-009 容错）
        }
        return usageByModel;
    }

    /** 单维度累计器。 */
    private static final class Agg {
        long calls;
        long tokens;
        long errors;
        double cost;

        void add(long tokens, boolean failed, double cost) {
            this.calls++;
            this.tokens += tokens;
            if (failed) this.errors++;
            this.cost += cost;
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "calls", calls,
                    "tokens", tokens,
                    "errors", errors,
                    "costCny", round2(cost),
                    "avgLatencyMs", 0);
        }
    }

    private static List<Map<String, Object>> toRows(Map<String, Agg> agg, String dim) {
        return agg.entrySet().stream()
                .sorted(Map.Entry.<String, Agg>comparingByValue(Comparator.comparingDouble(a -> -a.cost)))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(e.getValue().toMap());
                    row.put("dim", dim);
                    row.put("id", e.getKey());
                    row.put("name", e.getKey());
                    return row;
                })
                .toList();
    }

    /** 价格表（每千 token 人民币元，input/output 6:4）—— 与前端 PRICE_TABLE 同口径。 */
    private static double priceOf(String modelId, long tokens) {
        double perK = switch (modelId) {
            case "gpt-4o" -> 0.018 * 0.6 + 0.072 * 0.4;
            case "claude-3.7" -> 0.024 * 0.6 + 0.12 * 0.4;
            case "qwen-max" -> 0.008 * 0.6 + 0.024 * 0.4;
            case "deepseek-v3" -> 0.001 * 0.6 + 0.002 * 0.4;
            case "glm-4-plus" -> 0.007 * 0.6 + 0.021 * 0.4;
            default -> 0.01 * 0.6 + 0.03 * 0.4;
        };
        return tokens / 1000.0 * perK;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Duration parseRange(String range) {
        if (range.endsWith("h")) return Duration.ofHours(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        if (range.endsWith("d")) return Duration.ofDays(Math.max(1, Long.parseLong(range.substring(0, range.length() - 1))));
        return Duration.ofHours(24);
    }

    /** 租户解析：前端统一走 X-Tenant-Id 头（request.ts 自动注入），缺省 primary。 */
    private static String resolveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "primary" : tenantId;
    }

    private static String label(Instant t, Duration bucket) {
        return bucket.toHours() <= 1
                ? String.format("%02d:00", t.atZone(java.time.ZoneId.systemDefault()).getHour())
                : t.toString().substring(0, 10);
    }

    private static String actorDomain(String actor) {
        if (actor == null) return "unknown";
        int at = actor.indexOf('@');
        return at > 0 ? actor.substring(at + 1) : "unknown";
    }
}
