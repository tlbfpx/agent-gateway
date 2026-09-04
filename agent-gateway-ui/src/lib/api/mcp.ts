/**
 * mcp.ts — MCP 协议 client（Round 14 §mcp §5）
 *
 * 封装 JSON-RPC 2.0 envelope,后端实现细节参见:
 * gateway-interfaces/mcp/McpController.java
 */

import { getApiKey } from '../request';

export interface McpServer {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  transport: string;
  protocolVersion: string;
}

export interface McpTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface McpToolResult {
  isError: boolean;
  content: Array<{ type: string; text: string; mimeType?: string }>;
  metadata?: Record<string, unknown>;
}

interface JsonRpcResponse<T = unknown> {
  jsonrpc: '2.0';
  id?: string | number | null;
  result?: T;
  error?: { code: number; message: string };
}

const HEADERS = () => ({
  'X-API-Key': getApiKey(),
  'Content-Type': 'application/json',
});

let nextId = 1;
function newId(): string {
  return String(nextId++);
}

async function rpc<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const id = newId();
  const res = await fetch('/v1/mcp', {
    method: 'POST',
    headers: HEADERS(),
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  if (res.status === 204) {
    // notification — no body
    return undefined as unknown as T;
  }
  const body = (await res.json()) as JsonRpcResponse<T>;
  if (body.error) {
    throw new Error(`MCP ${method} → ${body.error.code}: ${body.error.message}`);
  }
  return body.result as T;
}

// ============= 标准 MCP 方法 =============

export const initialize = () => rpc<{
  protocolVersion: string;
  serverInfo: { name: string; version: string };
  capabilities: { tools: { listChanged: boolean } };
}>('initialize', {});

export const listTools = (serverId: string) => rpc<{
  server: McpServer;
  tools: McpTool[];
}>('tools/list', { serverId });

export const callTool = (
  serverId: string, name: string, arguments_: Record<string, unknown>,
) => rpc<McpToolResult>('tools/call', { serverId, name, arguments: arguments_ });

// ============= 管理端辅助 =============

export const listServers = async (): Promise<McpServer[]> => {
  const res = await fetch('/v1/mcp/servers', { headers: HEADERS() });
  if (!res.ok) throw new Error(`listServers → ${res.status}`);
  return (await res.json()) as McpServer[];
};

export const listServerTools = async (serverId: string): Promise<{
  server: McpServer;
  tools: McpTool[];
}> => {
  const res = await fetch(`/v1/mcp/servers/${encodeURIComponent(serverId)}/tools`, {
    headers: HEADERS(),
  });
  if (!res.ok) throw new Error(`listServerTools → ${res.status}`);
  return (await res.json()) as { server: McpServer; tools: McpTool[] };
};

/** 高阶便利：尝试 init + 列 tools(P0 demo 用) */
export const bootstrap = async (serverId: string) => {
  await initialize();
  return listTools(serverId);
};
