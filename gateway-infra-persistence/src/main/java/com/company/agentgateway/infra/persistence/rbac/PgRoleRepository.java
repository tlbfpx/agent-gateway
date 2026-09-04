package com.company.agentgateway.infra.persistence.rbac;

import com.company.agentgateway.domain.iam.AgentPermission;
import com.company.agentgateway.domain.iam.ModelPermission;
import com.company.agentgateway.domain.iam.Permission;
import com.company.agentgateway.domain.iam.Role;
import com.company.agentgateway.domain.iam.SkillPermission;
import com.company.agentgateway.domain.iam.RoleRepository;
import com.company.agentgateway.domain.shared.ModelId;
import com.company.agentgateway.domain.shared.RoleId;
import com.company.agentgateway.domain.shared.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * RoleRepository 的 PG 实现（add-pg-persistence）。
 *
 * <p>permissions 以 JSON 数组存储 sealed Permission 的显式类型映射
 * （agent / model / skill 三型，避免 Jackson 多态配置）。
 * 替代 InMemoryRoleRepository（保留为降级）；D1 接口签名零变化。
 */
public class PgRoleRepository implements RoleRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public PgRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Role> findById(TenantId tenant, RoleId roleId) {
        List<Role> rows = jdbc.query(
                "SELECT role_id, name, description, permissions FROM rbac_roles WHERE tenant_id = ? AND role_id = ?",
                (rs, i) -> mapRow(rs), tenant.value(), roleId.value());
        return rows.stream().findFirst();
    }

    @Override
    public List<Role> findAll(TenantId tenant) {
        return jdbc.query(
                "SELECT role_id, name, description, permissions FROM rbac_roles WHERE tenant_id = ? ORDER BY role_id",
                (rs, i) -> mapRow(rs), tenant.value());
    }

    @Override
    public void save(TenantId tenant, Role role) {
        jdbc.update("""
                INSERT INTO rbac_roles (tenant_id, role_id, name, description, permissions)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, role_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    permissions = EXCLUDED.permissions
                """,
                tenant.value(), role.id().value(), role.name(), role.description(),
                toJson(role.permissions()));
    }

    @Override
    public void delete(TenantId tenant, RoleId roleId) {
        jdbc.update("DELETE FROM rbac_roles WHERE tenant_id = ? AND role_id = ?",
                tenant.value(), roleId.value());
    }

    // ---- Permission JSON 映射（sealed 三型的显式编解码） ----

    private String toJson(Set<Permission> permissions) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Permission p : permissions) {
            ObjectNode n = MAPPER.createObjectNode();
            if (p instanceof AgentPermission ap) {
                n.put("type", "agent").put("agentName", ap.agentName());
                ArrayNode skills = n.putArray("allowedSkills");
                ap.allowedSkills().forEach(skills::add);
            } else if (p instanceof ModelPermission mp) {
                n.put("type", "model");
                ArrayNode models = n.putArray("models");
                mp.models().forEach(m -> models.add(m.value()));
            } else if (p instanceof SkillPermission sp) {
                n.put("type", "skill").put("agentName", sp.agentName()).put("skillName", sp.skillName());
            }
            arr.add(n);
        }
        return arr.toString();
    }

    private Role mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Set<Permission> permissions = new LinkedHashSet<>();
        try {
            JsonNode arr = MAPPER.readTree(rs.getString("permissions"));
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String type = n.path("type").asText();
                    switch (type) {
                        case "agent" -> {
                            Set<String> skills = new LinkedHashSet<>();
                            n.path("allowedSkills").forEach(s -> skills.add(s.asText()));
                            permissions.add(new AgentPermission(n.path("agentName").asText(), skills));
                        }
                        case "model" -> {
                            Set<ModelId> models = new LinkedHashSet<>();
                            n.path("models").forEach(m -> models.add(new ModelId(m.asText())));
                            permissions.add(new ModelPermission(models));
                        }
                        case "skill" -> permissions.add(new SkillPermission(
                                n.path("agentName").asText(), n.path("skillName").asText()));
                        default -> { /* 未知类型跳过（前向兼容） */ }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("rbac_roles.permissions JSON 解析失败: " + e.getMessage(), e);
        }
        return new Role(new RoleId(rs.getString("role_id")), rs.getString("name"),
                rs.getString("description"), permissions);
    }
}
