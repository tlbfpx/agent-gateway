package com.company.agentgateway.domain.iam;

import com.company.agentgateway.domain.shared.RoleId;
import java.util.Set;

/**
 * spec §19.2 Role 聚合根。字段上限（spec §GW-RBAC-012 契约）：
 * <ul>
 *   <li>{@code name}: 1-64 chars</li>
 *   <li>{@code description}: 0-256 chars</li>
 *   <li>{@code permissions.size}: ≤ 100</li>
 * </ul>
 */
public record Role(RoleId id, String name, String description, Set<Permission> permissions) {
    public Role {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("name must be ≤ 64 chars");
        }
        if (description != null && description.length() > 256) {
            throw new IllegalArgumentException("description must be ≤ 256 chars");
        }
        if (permissions == null || permissions.size() > 100) {
            throw new IllegalArgumentException("permissions must be ≤ 100");
        }
        permissions = Set.copyOf(permissions);
    }
}