package com.company.agentgateway.infra.config;

import com.company.agentgateway.domain.config.ConfigReloadBus;
import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.iam.SkillPermission;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 角色/权限持久化存储(Sprint 1 P0 §3.4):
 * 把内存中的 {@code Map<TenantId, Map<RoleId, Role>>} 落
 * {@code data/rbac.json},并在文件被外部修改时通过 {@link ConfigReloadBus}
 * 触发热重载。
 *
 * <h2>序列化策略</h2>
 * <p>domain 的 {@link Permission} 是 sealed 接口且不允许 Jackson 注解。
 * 因此本类用显式 DTO {@link RoleSpec} / {@link PermissionSpec} 转换:
 * <ul>
 *   <li>role 序列化为 {@code RoleSpec}:{@code {id, name, description, permissions: [{type, ...}]}}</li>
 *   <li>permission 按 {@code type} 字段反序列化(agent / model / skill)</li>
 * </ul>
 *
 * <h2>并发契约</h2>
 * <ul>
 *   <li>实现 {@link RoleRepository} 全部接口语义不变</li>
 *   <li>外部 reload → 替换全表 → 短暂不一致窗口(< 100ms)</li>
 *   <li>每次 save/delete 原子写盘(tmp + move)</li>
 * </ul>
 */
public class JsonFileRoleStore implements RoleRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileRoleStore.class);

    private final Path file;
    private final ObjectMapper mapper;
    private final ConfigReloadBus bus;
    private final Map<TenantId, Map<RoleId, Role>> store = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> reloadListeners = new CopyOnWriteArrayList<>();
    private ConfigReloadBus.Subscription reloadSub;

    public JsonFileRoleStore(Path file, ObjectMapper mapper, ConfigReloadBus bus) {
        this.file = file;
        this.mapper = mapper;
        this.bus = bus;
    }

    /** 启动:加载文件 + 订阅热重载。幂等。 */
    public void start() {
        load();
        if (reloadSub == null && bus != null) {
            reloadSub = bus.subscribe("rbac", event -> reload());
        }
        log.info("JsonFileRoleStore started: file={}", file);
    }

    /** 关闭:取消订阅。幂等。 */
    public void stop() {
        if (reloadSub != null) {
            bus.unsubscribe(reloadSub);
            reloadSub = null;
        }
    }

    /** 注册外部 reload 监听(供 UI 仪表盘等)。 */
    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    /** 强制重载(供测试 / 端点触发)。 */
    public void reload() {
        load();
        for (Runnable l : reloadListeners) {
            try { l.run(); } catch (RuntimeException e) { log.warn("rbac reload listener: {}", e.getMessage()); }
        }
    }

    // ─── RoleRepository 实现 ───

    @Override
    public Optional<Role> findById(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        return Optional.ofNullable(inner).map(m -> m.get(roleId));
    }

    @Override
    public List<Role> findAll(TenantId tenant) {
        Map<RoleId, Role> inner = store.get(tenant);
        return inner == null ? List.of() : List.copyOf(inner.values());
    }

    @Override
    public void save(TenantId tenant, Role role) {
        store.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>()).put(role.id(), role);
        persist();
    }

    @Override
    public void delete(TenantId tenant, RoleId roleId) {
        Map<RoleId, Role> inner = store.get(tenant);
        if (inner != null) inner.remove(roleId);
        persist();
    }

    // ─── 持久化 ───

    private void load() {
        if (!Files.exists(file)) {
            log.info("rbac file {} not found, starting with empty store", file);
            return;
        }
        try {
            FileShape data = mapper.readValue(file.toFile(), FileShape.class);
            store.clear();
            if (data == null || data.tenants == null) return;
            data.tenants.forEach((tenantIdStr, tenantBlock) -> {
                TenantId tid = new TenantId(tenantIdStr);
                Map<RoleId, Role> inner = new ConcurrentHashMap<>();
                if (tenantBlock != null && tenantBlock.roles != null) {
                    tenantBlock.roles.forEach(roleSpec -> {
                        Role role = toRole(roleSpec);
                        inner.put(role.id(), role);
                    });
                }
                store.put(tid, inner);
            });
            log.info("loaded rbac from {}: {} tenants, {} roles total",
                    file, store.size(), store.values().stream().mapToInt(Map::size).sum());
        } catch (IOException e) {
            log.error("failed to load rbac from {}: {} (keeping previous state)", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            FileShape data = new FileShape();
            data.tenants = new LinkedHashMap<>();
            store.forEach((tid, inner) -> {
                TenantBlock block = new TenantBlock();
                block.roles = new ArrayList<>();
                inner.values().forEach(role -> block.roles.add(fromRole(role)));
                data.tenants.put(tid.value(), block);
            });
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("failed to persist rbac to {}: {}", file, e.getMessage());
        }
    }

    // ─── 转换 ───

    private static Role toRole(RoleSpec spec) {
        Set<Permission> perms = spec.permissions == null ? Set.of() :
                spec.permissions.stream().map(JsonFileRoleStore::toPermission)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());
        RoleId rid = new RoleId(spec.id);
        return new Role(rid, spec.name, spec.description == null ? "" : spec.description, perms);
    }

    private static RoleSpec fromRole(Role role) {
        RoleSpec spec = new RoleSpec();
        spec.id = role.id().value();
        spec.name = role.name();
        spec.description = role.description();
        spec.permissions = role.permissions().stream()
                .map(JsonFileRoleStore::fromPermission)
                .toList();
        return spec;
    }

    private static Permission toPermission(PermissionSpec spec) {
        if (spec == null || spec.type == null) return null;
        return switch (spec.type) {
            case "agent" -> new AgentPermission(spec.agentName,
                    spec.allowedSkills == null ? Set.of() : Set.copyOf(spec.allowedSkills));
            case "model" -> new ModelPermission(parseModelIds(spec.models));
            case "skill" -> new SkillPermission(spec.skillAgent, spec.skillSkillName);
            default -> {
                log.warn("unknown permission type: {}", spec.type);
                yield null;
            }
        };
    }

    private static PermissionSpec fromPermission(Permission perm) {
        PermissionSpec spec = new PermissionSpec();
        if (perm instanceof AgentPermission ap) {
            spec.type = "agent";
            spec.agentName = ap.agentName();
            spec.allowedSkills = new ArrayList<>(ap.allowedSkills());
        } else if (perm instanceof ModelPermission mp) {
            spec.type = "model";
            spec.models = mp.models().stream().map(ModelId::value).toList();
        } else if (perm instanceof SkillPermission sp) {
            spec.type = "skill";
            spec.skillAgent = sp.agentName();
            spec.skillSkillName = sp.skillName();
        }
        return spec;
    }

    private static Set<ModelId> parseModelIds(List<String> raw) {
        if (raw == null) return Set.of();
        return raw.stream().map(ModelId::new).collect(Collectors.toUnmodifiableSet());
    }

    // ─── 持久化 DTO(domain 类不允许 Jackson 注解,在此处显式定义) ───

    /** 顶层结构:{"tenants":{"tenant-1":{"roles":[...]}}} */
    public static class FileShape {
        public Map<String, TenantBlock> tenants;
    }

    public static class TenantBlock {
        public List<RoleSpec> roles;
    }

    public static class RoleSpec {
        public String id;
        public String name;
        public String description;
        public List<PermissionSpec> permissions;
    }

    public static class PermissionSpec {
        /** "agent" | "model" | "skill" */
        public String type;
        /** AgentPermission 字段 */
        public String agentName;
        public List<String> allowedSkills;
        /** ModelPermission 字段 */
        public List<String> models;
        /** SkillPermission 字段(刻意加前缀避开 AgentPermission 的 agentName,避免 Jackson 同名字段映射冲突) */
        public String skillAgent;
        public String skillSkillName;
    }
}