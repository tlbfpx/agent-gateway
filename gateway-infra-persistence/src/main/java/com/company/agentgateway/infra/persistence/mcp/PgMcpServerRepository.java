package com.company.agentgateway.infra.persistence.mcp;

import com.company.agentgateway.domain.mcp.McpPort;
import com.company.agentgateway.domain.mcp.McpServer;
import com.company.agentgateway.domain.mcp.McpTool;
import com.company.agentgateway.domain.mcp.McpToolCall;
import com.company.agentgateway.domain.mcp.McpToolResult;
import com.company.agentgateway.domain.mcp.McpTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * McpServer Pg 实现（spec 2026-09-02 §mcp §4 P1, R20 #1）。
 *
 * <p>设计：混合持久化
 * <ul>
 *   <li><b>DB 持久化</b>：McpServer 注册表（id/name/description/endpoint/transport/protocol_version）。
 *       重启不丢，多副本一致。</li>
 *   <li><b>内存保留</b>：tools 列表 + tool call handlers。运行时行为，DB 化无意义
 *       （handler 是 Java lambda，无法序列化）。</li>
 * </ul>
 *
 * <p>实现策略：包一个 InMemoryMcpServerRepository，server 元数据走 DB（写入/查询/加载），
 * tools/handlers 走包装的 InMemory（listTools / callTool）。
 *
 * <p>启动时 {@link #loadAllFromDb()} 把 DB 里所有 server 加载到包装 InMemory，
 * 这样 listTools 仍能在已注册 server 上工作。
 */
public class PgMcpServerRepository implements McpPort {

    private static final Logger log = LoggerFactory.getLogger(PgMcpServerRepository.class);

    private final JdbcTemplate jdbc;
    private final InMemoryMcpServerRepository delegate;

    public PgMcpServerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.delegate = new InMemoryMcpServerRepository();
        loadAllFromDb();
    }

    /**
     * 启动时把 DB 里所有 McpServer 加载到 InMemory，使 listTools/callTool 仍能工作。
     * 不覆盖 InMemory 内置 tools/handlers（那些是设计时定死的，不在 DB 里）。
     */
    private void loadAllFromDb() {
        List<McpServer> all = jdbc.query(SELECT_ALL, MAPPER);
        for (McpServer s : all) {
            // 跳过 InMemory 内置的 (它注册时已自带 tools/handlers)
            if (s.id().startsWith("builtin-")) continue;
            delegate.registerServer(s);
            log.info("mcp.pg.loaded id={} name={} endpoint={}", s.id(), s.name(), s.endpoint());
        }
    }

    private static final String SELECT_ALL = """
            SELECT id, name, description, endpoint, transport, protocol_version
            FROM mcp_server
            ORDER BY created_at
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, name, description, endpoint, transport, protocol_version
            FROM mcp_server
            WHERE id = ?
            """;

    private static final String COUNT_BY_ID = "SELECT COUNT(*) FROM mcp_server WHERE id = ?";

    private static final String INSERT = """
            INSERT INTO mcp_server (id, name, description, endpoint, transport, protocol_version, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;

    private static final String UPDATE = """
            UPDATE mcp_server
            SET name = ?, description = ?, endpoint = ?, transport = ?, protocol_version = ?
            WHERE id = ?
            """;

    private static final RowMapper<McpServer> MAPPER = (rs, n) -> {
        String transportName = rs.getString("transport");
        McpTransport transport = transportName == null
                ? McpTransport.HTTP_STREAMABLE
                : McpTransport.valueOf(transportName);
        return new McpServer(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("endpoint"),
                transport,
                rs.getString("protocol_version"));
    };

    @Override
    public McpServer register(McpServer server) {
        // 可移植 UPSERT: H2 / PostgreSQL 都支持
        // 不用 ON CONFLICT (Pg 专属, H2 2.x 仍不识别)
        Integer existing = jdbc.queryForObject(COUNT_BY_ID, Integer.class, server.id());
        if (existing != null && existing > 0) {
            jdbc.update(UPDATE,
                    server.name(),
                    server.description(),
                    server.endpoint(),
                    server.transport().name(),
                    server.protocolVersion(),
                    server.id());
        } else {
            jdbc.update(INSERT,
                    server.id(),
                    server.name(),
                    server.description(),
                    server.endpoint(),
                    server.transport().name(),
                    server.protocolVersion());
        }
        delegate.registerServer(server);
        log.info("mcp.pg.registered id={} name={}", server.id(), server.name());
        return server;
    }

    @Override
    public Optional<McpServer> findById(String id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(SELECT_BY_ID, MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<McpServer> listAll() {
        return jdbc.query(SELECT_ALL, MAPPER);
    }

    @Override
    public List<McpTool> listTools(String serverId) {
        // tools 仍由 InMemory 提供 (handler 不可序列化)
        return delegate.listTools(serverId);
    }

    @Override
    public McpToolResult callTool(McpToolCall call) {
        return delegate.callTool(call);
    }
}
