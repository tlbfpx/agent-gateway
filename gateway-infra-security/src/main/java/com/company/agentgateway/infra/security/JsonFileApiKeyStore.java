package com.company.agentgateway.infra.security;

import com.company.agentgateway.domain.iam.AgentGrant;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.TenantId;
import com.company.agentgateway.domain.shared.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 可写 API Key 存储：admin 签发/吊销动态持久化到本地 JSON（重启不丢）。
 *
 * <p>文件格式（{@code gateway.security.key-file}，默认 data/api-keys.json）：
 * <pre>
 * [ {"key":"sk-...","tenant":"t1","user":"u1","revoked":false,
 *    "agentGrants":["echo-agent"],"allowedModels":["minimax-abab6.5s-chat"]} ]
 * </pre>
 *
 * <p>与 {@link InMemoryApiKeyStore} 同接口；写路径：内存先更新 → 原子写盘（tmp+move）。
 * fail-safe：文件损坏时空表启动（不阻塞应用）；坏条目跳过。
 */
public class JsonFileApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileApiKeyStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, ApiKeyBinding> bindings = new ConcurrentHashMap<>();

    @Override
    public java.util.List<java.util.Map.Entry<String, ApiKeyBinding>> entries() {
        return java.util.List.copyOf(bindings.entrySet());
    }

    public JsonFileApiKeyStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            log.info("ApiKey file {} not found, starting with 0 dynamic key(s)", file);
            return;
        }
        try {
            CollectionType type = MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class);
            List<Map<String, Object>> raw = MAPPER.readValue(file.toFile(), type);
            raw.forEach(this::putFromMap);
            log.info("ApiKey store loaded {} key(s) from {}", bindings.size(), file);
        } catch (Exception e) {
            log.error("Failed to load api-keys {} (starting empty): {}", file, e.getMessage());
        }
    }

    /** 签发/注册（持久化）。 */
    public void register(String apiKey, ApiKeyBinding binding) {
        bindings.put(apiKey, binding);
        persist();
    }

    /** 吊销（从文件删除，非软标记——简单直接）。 */
    public void revoke(String apiKey) {
        bindings.remove(apiKey);
        persist();
    }

    @Override
    public Optional<ApiKeyBinding> findByKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindings.get(apiKey))
                .filter(b -> !b.revoked())
                .filter(b -> !b.isExpired(java.time.Instant.now()));
    }

    /** 列出全部（管理端展示用，key 脱敏由调用方做）。 */
    public List<String> listKeys() {
        return List.copyOf(bindings.keySet());
    }

    /** 写盘后回调（装配层注入 ConfigHistory.snapshot；缺省无操作）。 */
    private Runnable onPersist = () -> {};

    public void setOnPersist(Runnable r) { this.onPersist = r; }

    private synchronized void persist() {
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> out = bindings.entrySet().stream()
                    .map(e -> toMap(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), out);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to persist api-keys to {}: {}", file, e.getMessage());
            return;
        }
        onPersist.run();
    }

    private void putFromMap(Map<String, Object> cfg) {
        try {
            String key = str(cfg, "key");
            String tenant = str(cfg, "tenant");
            String user = str(cfg, "user");
            if (key == null || tenant == null || user == null) {
                log.warn("Skip api-key entry without key/tenant/user");
                return;
            }
            // 多租户授权列表（spec §6.2 二期）：文件含 tenants 字段时优先采用，缺省回退为 {tenant}。
            LinkedHashSet<TenantId> tenants = new LinkedHashSet<>();
            tenants.add(new TenantId(tenant));
            Object rawTenants = cfg.get("tenants");
            if (rawTenants instanceof List<?> list) {
                list.stream()
                        .map(o -> new TenantId(String.valueOf(o).trim()))
                        .forEach(tenants::add);
            }
            bindings.put(key, new ApiKeyBinding(
                    new TenantId(tenant),
                    new UserId(user),
                    grants(cfg.get("agentGrants")),
                    models(cfg.get("allowedModels")),
                    Boolean.parseBoolean(String.valueOf(cfg.getOrDefault("revoked", "false"))),
                    tenants,
                    expiresAt(cfg.get("expiresAt"))));
        } catch (Exception e) {
            log.warn("Skip bad api-key entry: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toMap(String key, ApiKeyBinding b) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("key", key);
        m.put("tenant", b.tenant().value());
        m.put("user", b.user().value());
        m.put("agentGrants", b.agentGrants().stream().map(AgentGrant::agentName).toList());
        m.put("allowedModels", b.allowedModels().stream().map(ModelId::value).toList());
        m.put("revoked", b.revoked());
        m.put("tenants", b.tenants().stream().map(TenantId::value).toList());
        if (b.expiresAt() != null) {
            m.put("expiresAt", b.expiresAt().toString());
        }
        return m;
    }

    /** expiresAt 解析：ISO-8601 字符串（如 2026-12-31T23:59:59Z）；空/非法返回 null（永不过期）。 */
    private static java.time.Instant expiresAt(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            return java.time.Instant.parse(s);
        } catch (Exception e) {
            log.warn("Skip bad expiresAt '{}': {}", s, e.getMessage());
            return null;
        }
    }

    private static String str(Map<String, Object> cfg, String k) {
        Object v = cfg.get(k);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static Set<AgentGrant> grants(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(a -> new AgentGrant(String.valueOf(a), Set.of()))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private static Set<ModelId> models(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(m -> new ModelId(String.valueOf(m)))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }
}
