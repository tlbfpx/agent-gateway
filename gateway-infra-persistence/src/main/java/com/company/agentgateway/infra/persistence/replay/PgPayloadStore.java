package com.company.agentgateway.infra.persistence.replay;

import com.company.agentgateway.domain.replay.PayloadCapturePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * trace_payloads PG 仓储(Sprint 2 P0):
 * <ul>
 *   <li>落盘:AES-256-GCM(body_enc)</li>
 *   <li>读盘:解密还原</li>
 *   <li>retention 清理:purgeBefore(cutoff)</li>
 * </ul>
 *
 * <p>加密由 {@link PayloadCipher} 处理;密钥从装配层注入(由 secret store 派生)。
 */
public class PgPayloadStore implements PayloadCapturePort {

    private static final Logger log = LoggerFactory.getLogger(PgPayloadStore.class);

    private final DataSource dataSource;
    private final PayloadCipher cipher;

    public PgPayloadStore(DataSource dataSource, PayloadCipher cipher) {
        this.dataSource = dataSource;
        this.cipher = cipher;
    }

    @Override
    public boolean capture(PayloadRecord record) {
        String sql = """
                INSERT INTO trace_payloads
                    (trace_id, span_id, role, content_type, body_enc, bytes, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trace_id, span_id, role, captured_at) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.traceId());
            ps.setString(2, record.spanId() == null ? "" : record.spanId());
            ps.setString(3, record.role().name());
            ps.setString(4, record.contentType());
            ps.setBytes(5, cipher.encrypt(record.body()));
            ps.setInt(6, record.bytes());
            ps.setTimestamp(7, Timestamp.from(record.capturedAt()));
            ps.executeUpdate();
            return true;
        } catch (SQLException | RuntimeException e) {
            log.warn("capture failed for trace={} role={}: {}",
                    record.traceId(), record.role(), e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<PayloadRecord> findByTraceAndRole(String traceId, Role role) {
        List<PayloadRecord> all = findByTrace(traceId);
        return all.stream().filter(p -> p.role() == role).findFirst();
    }

    @Override
    public List<PayloadRecord> findByTrace(String traceId) {
        String sql = """
                SELECT trace_id, span_id, role, content_type, body_enc, bytes, captured_at
                  FROM trace_payloads
                 WHERE trace_id = ?
                 ORDER BY captured_at ASC
                """;
        List<PayloadRecord> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        byte[] enc = rs.getBytes("body_enc");
                        String body = cipher.decrypt(enc);
                        Role role = Role.valueOf(rs.getString("role"));
                        out.add(new PayloadRecord(
                                rs.getString("trace_id"),
                                rs.getString("span_id"),
                                role,
                                rs.getString("content_type"),
                                body,
                                rs.getInt("bytes"),
                                rs.getTimestamp("captured_at").toInstant()));
                    } catch (Exception e) {
                        log.warn("decrypt failed for trace={}: {}", traceId, e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("findByTrace failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM trace_payloads WHERE captured_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("purgeBefore failed: {}", e.getMessage());
            return 0;
        }
    }
}