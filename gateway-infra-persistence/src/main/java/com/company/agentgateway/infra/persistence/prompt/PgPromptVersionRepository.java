package com.company.agentgateway.infra.persistence.prompt;

import com.company.agentgateway.domain.prompt.PromptVersion;
import com.company.agentgateway.domain.prompt.PromptVersionRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PromptVersion Pg 实现（spec 2026-09-02 §pg-persistence §4.4）。
 */
public class PgPromptVersionRepository implements PromptVersionRepository {

    private final JdbcTemplate jdbc;

    public PgPromptVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PromptVersion> MAPPER = (rs, n) -> {
        Instant created = rs.getTimestamp("created_at").toInstant();
        String paramsJson = rs.getString("params");
        Map<String, Object> params = paramsJson == null || paramsJson.isBlank()
                ? Map.of()
                : parseJson(paramsJson);
        return new PromptVersion(
                rs.getLong("id"),
                rs.getLong("template_id"),
                rs.getInt("version"),
                rs.getString("system_prompt"),
                rs.getString("user_prompt"),
                rs.getString("model"),
                params,
                rs.getLong("author_id"),
                created);
    };

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public PromptVersion save(PromptVersion version) {
        if (version.id() == 0) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO prompt_version (template_id, version, system_prompt, user_prompt, model, params, author_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new String[] { "id" });
                ps.setLong(1, version.templateId());
                ps.setInt(2, version.version());
                ps.setString(3, version.systemPrompt());
                ps.setString(4, version.userPrompt());
                ps.setString(5, version.model());
                ps.setString(6, serializeParams(version));
                ps.setLong(7, version.authorId());
                ps.setTimestamp(8, Timestamp.from(version.createdAt()));
                return ps;
            }, kh);
            return findById(kh.getKey().longValue()).orElseThrow();
        }
        jdbc.update("UPDATE prompt_version SET system_prompt=?, user_prompt=?, model=?, params=?, author_id=? WHERE id=?",
                version.systemPrompt(), version.userPrompt(), version.model(),
                serializeParams(version), version.authorId(), version.id());
        return version;
    }

    @Override
    public Optional<PromptVersion> findById(long id) {
        return jdbc.query("SELECT * FROM prompt_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<PromptVersion> findByVersion(long templateId, int version) {
        return jdbc.query("SELECT * FROM prompt_version WHERE template_id = ? AND version = ?",
                        MAPPER, templateId, version).stream().findFirst();
    }

    @Override
    public List<PromptVersion> findByTemplate(long templateId) {
        return jdbc.query(
                "SELECT * FROM prompt_version WHERE template_id = ? ORDER BY version DESC",
                MAPPER, templateId);
    }

    @Override
    public boolean deleteByTemplate(long templateId) {
        return jdbc.update("DELETE FROM prompt_version WHERE template_id = ?", templateId) > 0;
    }

    private static String serializeParams(PromptVersion v) {
        if (v.params() == null || v.params().isEmpty()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v.params());
        } catch (Exception e) {
            return null;
        }
    }
}