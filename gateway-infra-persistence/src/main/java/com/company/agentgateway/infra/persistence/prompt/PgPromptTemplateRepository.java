package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptTemplate;
import com.company.agentgateway.domain.prompt.PromptTemplateRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PromptTemplate Pg 实现（spec 2026-09-02 §pg-persistence §4.4）。
 */
public class PgPromptTemplateRepository implements PromptTemplateRepository {

    private final JdbcTemplate jdbc;

    public PgPromptTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PromptTemplate> MAPPER = (rs, n) -> {
        Instant created = rs.getTimestamp("created_at").toInstant();
        Instant updated = rs.getTimestamp("updated_at").toInstant();
        String tagsCsv = rs.getString("tags");
        java.util.List<String> tags = tagsCsv == null || tagsCsv.isBlank()
                ? java.util.List.of()
                : java.util.List.of(tagsCsv.split("\\|"));
        return new PromptTemplate(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("owner_id"),
                rs.getString("tenant_id"),
                tags,
                created,
                updated);
    };

    @Override
    public PromptTemplate save(PromptTemplate template) {
        if (template.id() == 0) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO prompt_template (name, description, owner_id, tenant_id, tags, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        new String[] { "id" });
                ps.setString(1, template.name());
                ps.setString(2, template.description() == null ? "" : template.description());
                ps.setLong(3, template.ownerId());
                ps.setString(4, template.tenantId());
                ps.setString(5, String.join("|", template.tags()));
                ps.setTimestamp(6, Timestamp.from(template.createdAt()));
                ps.setTimestamp(7, Timestamp.from(template.updatedAt()));
                return ps;
            }, kh);
            return findById(kh.getKey().longValue()).orElseThrow();
        }
        jdbc.update("UPDATE prompt_template SET name=?, description=?, owner_id=?, tenant_id=?, tags=?, updated_at=? WHERE id=?",
                template.name(),
                template.description() == null ? "" : template.description(),
                template.ownerId(),
                template.tenantId(),
                String.join("|", template.tags()),
                Timestamp.from(template.updatedAt()),
                template.id());
        return template;
    }

    @Override
    public Optional<PromptTemplate> findById(long id) {
        return jdbc.query("SELECT * FROM prompt_template WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<PromptTemplate> findByName(String tenantId, String name) {
        return jdbc.query("SELECT * FROM prompt_template WHERE tenant_id = ? AND name = ?",
                        MAPPER, tenantId, name).stream().findFirst();
    }

    @Override
    public List<PromptTemplate> findByTenant(String tenantId) {
        return jdbc.query(
                "SELECT * FROM prompt_template WHERE tenant_id = ? ORDER BY updated_at DESC",
                MAPPER, tenantId);
    }

    @Override
    public List<PromptTemplate> query(Query q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM prompt_template WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (q.tenantId() != null) { sql.append(" AND tenant_id = ?"); args.add(q.tenantId()); }
        if (q.ownerId() > 0) { sql.append(" AND owner_id = ?"); args.add(q.ownerId()); }
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        args.add(q.limit());
        args.add(q.offset());
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM prompt_template WHERE id = ?", id) > 0;
    }
}