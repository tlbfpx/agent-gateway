package com.company.agentgateway.domain.iam.admin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队（spec 2026-09-02 §multi-admin §5）。
 *
 * <p>一个团队属于一个租户，由 {@code ownerId}（AdminUser.id）创建并拥有；
 * {@code memberIds} 是 AdminUser.id 列表（不含 owner）。
 * 不可变 record;通过 {@code withMembers} 生成新实例做成员变更。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code id} —— 主键;0 未持久化</li>
 *   <li>{@code name} —— 团队名(租户内唯一)</li>
 *   <li>{@code tenantId} —— 所属租户</li>
 *   <li>{@code ownerId} —— Owner AdminUser.id</li>
 *   <li>{@code memberIds} —— 成员 AdminUser.id 列表(不含 owner)</li>
 *   <li>{@code createdAt} —— 创建时间</li>
 * </ul>
 */
public record Team(
        long id,
        String name,
        String tenantId,
        long ownerId,
        Set<Long> memberIds,
        Instant createdAt) {

    public Team {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be > 0, got " + ownerId);
        }
        if (memberIds == null) {
            memberIds = Set.of();
        } else {
            // 不允许 ownerId 同时出现在 memberIds
            if (memberIds.contains(ownerId)) {
                memberIds = memberIds.stream().filter(memberId -> memberId != ownerId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            } else {
                memberIds = Set.copyOf(memberIds);
            }
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** 构造一条待持久化的 Team(id=0, memberIds=空, createdAt=now) */
    public static Team create(String name, String tenantId, long ownerId) {
        return new Team(0L, name, tenantId, ownerId, Set.of(), Instant.now());
    }

    /** 含新成员集合的不可变副本（add/remove Member 用） */
    public Team withMembers(Set<Long> newMembers) {
        return new Team(id, name, tenantId, ownerId, newMembers, createdAt);
    }

    public boolean isMember(long adminUserId) {
        return adminUserId == ownerId || memberIds.contains(adminUserId);
    }

    public int size() {
        return 1 + memberIds.size();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("tenantId", tenantId);
        m.put("ownerId", ownerId);
        m.put("memberIds", List.copyOf(memberIds));
        m.put("size", size());
        m.put("createdAt", createdAt.toString());
        return m;
    }
}
