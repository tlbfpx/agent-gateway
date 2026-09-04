-- R20 #1 McpServer Pg 持久化 (Round 14 §mcp §4 P1)
-- Flyway migration: V2
-- 适用 PostgreSQL 14+ / H2 (PostgreSQL mode)
-- 解决 known-limitations.md §1.3: McpServer 重启即丢
--
-- 设计:
-- * 只持久化 McpServer 元数据 (注册信息)
-- * tools/handlers 仍在内存 (运行时行为, 不适合 DB)
-- * 启动时 PgMcpServerRepository 加载 server 列表, 工具执行走 InMemory 包装

CREATE TABLE IF NOT EXISTS mcp_server (
    id               VARCHAR(128) PRIMARY KEY,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    endpoint         VARCHAR(512) NOT NULL,
    transport        VARCHAR(32)  NOT NULL DEFAULT 'HTTP_STREAMABLE',
    protocol_version VARCHAR(32)  NOT NULL DEFAULT '2025-06-18',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 内置 2 个示例 server (与 InMemory 默认值一致)
INSERT INTO mcp_server (id, name, description, endpoint, transport, protocol_version)
VALUES
    ('builtin-time', 'Time Utils', '时间格式化与时区工具', 'internal://builtin/time', 'HTTP_STREAMABLE', '2025-06-18'),
    ('builtin-echo', 'Echo Tools', 'echo / reverse / upper 工具集', 'internal://builtin/echo', 'HTTP_STREAMABLE', '2025-06-18')
ON CONFLICT (id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_mcp_server_created ON mcp_server (created_at);
