package com.company.agentgateway.application.admin;

import com.company.agentgateway.domain.iam.admin.AdminRole;
import com.company.agentgateway.domain.iam.admin.AdminUser;
import com.company.agentgateway.domain.iam.admin.AdminUserRepository;
import com.company.agentgateway.domain.iam.admin.Team;
import com.company.agentgateway.domain.iam.admin.TeamRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Team 用例层（spec 2026-09-02 §multi-admin §5.3）。
 *
 * <p>Team 操作都需要 ADMIN+ 角色;Owner 转让需 OWNER 权限。
 */
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teamRepository;
    private final AdminUserRepository userRepository;

    public TeamService(TeamRepository teamRepository, AdminUserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
    }

    public Team create(String name, String tenantId, long ownerId, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        // owner 必须存在且 ACTIVE
        AdminUser owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("owner not found: " + ownerId));
        if (!owner.status().canLogin()) {
            throw new IllegalStateException("owner not active: " + ownerId);
        }
        // 同租户 + 同名 唯一
        if (teamRepository.findByName(tenantId, name).isPresent()) {
            throw new IllegalStateException("team name already exists: " + name);
        }
        Team saved = teamRepository.save(Team.create(name, tenantId, ownerId));
        log.info("team.created id={} name={} owner={} by={}", saved.id(), name, ownerId, callerRole);
        return saved;
    }

    public Team addMember(long teamId, long memberId, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        Team t = mustFind(teamId);
        AdminUser member = userRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("member not found: " + memberId));
        if (!member.tenantId().equals(t.tenantId())) {
            throw new IllegalArgumentException("member tenant mismatch");
        }
        if (memberId == t.ownerId()) {
            return t; // idempotent
        }
        return teamRepository.save(t.withMembers(append(t.memberIds(), memberId)));
    }

    public Team removeMember(long teamId, long memberId, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.ADMIN);
        Team t = mustFind(teamId);
        if (memberId == t.ownerId()) {
            throw new IllegalArgumentException("cannot remove owner; transfer first");
        }
        return teamRepository.save(t.withMembers(remove(t.memberIds(), memberId)));
    }

    /** 转让所有权 —— 仅 OWNER 可触发;目标用户必须同租户 + ACTIVE。 */
    public Team transferOwnership(long teamId, long newOwnerId, AdminRole callerRole) {
        requireCaller(callerRole, AdminRole.OWNER);
        Team t = mustFind(teamId);
        AdminUser newOwner = userRepository.findById(newOwnerId)
                .orElseThrow(() -> new IllegalArgumentException("new owner not found: " + newOwnerId));
        if (!newOwner.tenantId().equals(t.tenantId())) {
            throw new IllegalArgumentException("new owner tenant mismatch");
        }
        if (!newOwner.status().canLogin()) {
            throw new IllegalStateException("new owner not active: " + newOwnerId);
        }
        // 把旧 owner 加到 members(若不在);新 owner 不再是 member
        java.util.Set<Long> nextMembers = new java.util.HashSet<>(t.memberIds());
        nextMembers.remove(newOwnerId);
        nextMembers.add(t.ownerId());
        Team updated = new Team(t.id(), t.name(), t.tenantId(),
                newOwnerId, nextMembers, t.createdAt());
        Team saved = teamRepository.save(updated);
        log.info("team.transferred id={} owner: {} → {} by={}",
                teamId, t.ownerId(), newOwnerId, callerRole);
        return saved;
    }

    public List<Team> findByTenant(String tenantId) { return teamRepository.findByTenant(tenantId); }

    public Team findById(long id) {
        return mustFind(id);
    }

    private Team mustFind(long id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("team not found: " + id));
    }

    private static void requireCaller(AdminRole caller, AdminRole required) {
        if (caller == null || !caller.atLeast(required)) {
            throw new SecurityException("caller role " + caller + " insufficient (need " + required + ")");
        }
    }

    private static java.util.Set<Long> append(java.util.Set<Long> set, long id) {
        java.util.Set<Long> next = new java.util.HashSet<>(set);
        next.add(id);
        return java.util.Set.copyOf(next);
    }

    private static java.util.Set<Long> remove(java.util.Set<Long> set, long id) {
        java.util.Set<Long> next = new java.util.HashSet<>(set);
        next.remove(id);
        return java.util.Set.copyOf(next);
    }
}
