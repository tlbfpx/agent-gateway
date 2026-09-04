package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.Team;
import com.company.agentgateway.domain.iam.admin.TeamRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Team 内存实现（spec 2026-09-02 §multi-admin §5.2 P0）。
 */
public class InMemoryTeamRepository implements TeamRepository {

    private final CopyOnWriteArrayList<Team> teams = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public Team save(Team team) {
        if (team.id() == 0) {
            long id = nextId.getAndIncrement();
            Team persisted = new Team(id, team.name(), team.tenantId(),
                    team.ownerId(), team.memberIds(), team.createdAt());
            teams.add(persisted);
            return persisted;
        }
        teams.removeIf(t -> t.id() == team.id());
        teams.add(team);
        return team;
    }

    @Override
    public Optional<Team> findById(long id) {
        return teams.stream().filter(t -> t.id() == id).findFirst();
    }

    @Override
    public List<Team> findByTenant(String tenantId) {
        return teams.stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Team::createdAt).reversed())
                .toList();
    }

    @Override
    public List<Team> findByOwner(long ownerId) {
        return teams.stream()
                .filter(t -> t.ownerId() == ownerId)
                .sorted(Comparator.comparing(Team::createdAt).reversed())
                .toList();
    }

    @Override
    public List<Team> findByMember(long adminUserId) {
        return teams.stream()
                .filter(t -> t.ownerId() == adminUserId || t.memberIds().contains(adminUserId))
                .sorted(Comparator.comparing(Team::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<Team> findByName(String tenantId, String name) {
        return teams.stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    @Override
    public boolean delete(long id) {
        int before = teams.size();
        teams.removeIf(t -> t.id() == id);
        return before != teams.size();
    }

    @Override
    public List<Team> query(TeamQuery query) {
        return teams.stream()
                .filter(t -> query.tenantId() == null || t.tenantId().equals(query.tenantId()))
                .filter(t -> query.ownerId() == null || t.ownerId() == query.ownerId())
                .sorted(Comparator.comparing(Team::createdAt).reversed())
                .skip(query.offset())
                .limit(query.limit())
                .toList();
    }

    /** 便捷方法：添加成员并持久化（应用层调用） */
    public Team addMember(Team team, long memberId) {
        Set<Long> next = new java.util.HashSet<>(team.memberIds());
        next.add(memberId);
        next.remove(team.ownerId());
        Team updated = team.withMembers(next);
        save(updated);
        return updated;
    }

    /** 便捷方法：移除成员并持久化（应用层调用） */
    public Team removeMember(Team team, long memberId) {
        if (memberId == team.ownerId()) {
            throw new IllegalArgumentException("cannot remove owner; transfer ownership first");
        }
        Set<Long> next = new java.util.HashSet<>(team.memberIds());
        next.remove(memberId);
        Team updated = team.withMembers(next);
        save(updated);
        return updated;
    }
}
