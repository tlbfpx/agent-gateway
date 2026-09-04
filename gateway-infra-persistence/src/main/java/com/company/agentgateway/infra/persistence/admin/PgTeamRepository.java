package com.company.agentgateway.infra.persistence.admin;

import com.company.agentgateway.domain.iam.admin.Team;
import com.company.agentgateway.domain.iam.admin.TeamRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Team Pg 实现（spec 2026-09-02 §pg-persistence §4.3）。
 *
 * <p>Team 表 + team_member 多对多表。
 */
public class PgTeamRepository implements TeamRepository {

    private final JdbcTemplate jdbc;

    public PgTeamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Team> BASE_MAPPER = (rs, n) -> new Team(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("tenant_id"),
            rs.getLong("owner_id"),
            new HashSet<>(),  // members 单独查询
            rs.getTimestamp("created_at").toInstant());

    private Team loadMembers(Team base) {
        List<Long> memberIds = jdbc.queryForList(
                "SELECT admin_id FROM team_member WHERE team_id = ?", Long.class, base.id());
        return base.withMembers(new HashSet<>(memberIds));
    }

    @Override
    public Team save(Team team) {
        if (team.id() == 0) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO team (name, tenant_id, owner_id, created_at) VALUES (?, ?, ?, ?)",
                        new String[] { "id" });
                ps.setString(1, team.name());
                ps.setString(2, team.tenantId());
                ps.setLong(3, team.ownerId());
                ps.setTimestamp(4, Timestamp.from(team.createdAt()));
                return ps;
            }, kh);
            long id = kh.getKey().longValue();
            for (Long memberId : team.memberIds()) {
                jdbc.update("INSERT INTO team_member (team_id, admin_id) VALUES (?, ?)", id, memberId);
            }
            return findById(id).orElseThrow();
        }
        jdbc.update("UPDATE team SET name=?, tenant_id=?, owner_id=? WHERE id=?",
                team.name(), team.tenantId(), team.ownerId(), team.id());
        jdbc.update("DELETE FROM team_member WHERE team_id = ?", team.id());
        for (Long memberId : team.memberIds()) {
            jdbc.update("INSERT INTO team_member (team_id, admin_id) VALUES (?, ?)", team.id(), memberId);
        }
        return team;
    }

    @Override
    public Optional<Team> findById(long id) {
        return jdbc.query("SELECT * FROM team WHERE id = ?", BASE_MAPPER, id)
                .stream().findFirst().map(this::loadMembers);
    }

    @Override
    public List<Team> findByTenant(String tenantId) {
        return jdbc.query(
                "SELECT * FROM team WHERE tenant_id = ? ORDER BY created_at DESC",
                BASE_MAPPER, tenantId).stream().map(this::loadMembers).toList();
    }

    @Override
    public List<Team> findByOwner(long ownerId) {
        return jdbc.query(
                "SELECT * FROM team WHERE owner_id = ? ORDER BY created_at DESC",
                BASE_MAPPER, ownerId).stream().map(this::loadMembers).toList();
    }

    @Override
    public List<Team> findByMember(long adminUserId) {
        return jdbc.query(
                "SELECT t.* FROM team t JOIN team_member m ON t.id = m.team_id " +
                "WHERE m.admin_id = ? OR t.owner_id = ? ORDER BY t.created_at DESC",
                BASE_MAPPER, adminUserId, adminUserId).stream().map(this::loadMembers).toList();
    }

    @Override
    public Optional<Team> findByName(String tenantId, String name) {
        return jdbc.query("SELECT * FROM team WHERE tenant_id = ? AND name = ?",
                        BASE_MAPPER, tenantId, name).stream().findFirst().map(this::loadMembers);
    }

    @Override
    public boolean delete(long id) {
        jdbc.update("DELETE FROM team_member WHERE team_id = ?", id);
        return jdbc.update("DELETE FROM team WHERE id = ?", id) > 0;
    }

    @Override
    public List<Team> query(TeamQuery q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM team WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (q.tenantId() != null) { sql.append(" AND tenant_id = ?"); args.add(q.tenantId()); }
        if (q.ownerId() > 0) { sql.append(" AND owner_id = ?"); args.add(q.ownerId()); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(q.limit());
        args.add(q.offset());
        return jdbc.query(sql.toString(), BASE_MAPPER, args.toArray()).stream()
                .map(this::loadMembers).toList();
    }
}