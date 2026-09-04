import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Input, Row, Space, Table, Tabs, Tag, message,
  type TableColumnsType,
} from 'antd';
import { CodeOutlined, PlayCircleOutlined, ReloadOutlined, ApiOutlined } from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import {
  initialize, listServers, listServerTools, callTool,
  type McpServer, type McpTool,
} from '../lib/api/mcp';

const ALLOWED_ARGS_KEY = 'mcp.lastArgs';

/**
 * /mcp MCP 协议探索页（Round 14 §mcp §7 UI）。
 *
 * 列出已注册 MCP server + 每个 server 的工具;支持选 server + 工具 + 测试调用。
 */
export function Mcp() {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [selectedServerId, setSelectedServerId] = useState<string | null>(null);
  const [tools, setTools] = useState<McpTool[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [capabilities, setCapabilities] = useState<any>(null);
  const [selectedTool, setSelectedTool] = useState<McpTool | null>(null);
  const [argsText, setArgsText] = useState<string>(
    localStorage.getItem(ALLOWED_ARGS_KEY) ?? '{}',
  );
  const [callResult, setCallResult] = useState<string>('');
  const [calling, setCalling] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    listServers()
      .then((s) => {
        setServers(s);
        if (s.length > 0 && !selectedServerId) setSelectedServerId(s[0].id);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, []);

  useEffect(() => {
    if (!selectedServerId) {
      setTools([]);
      return;
    }
    listServerTools(selectedServerId)
      .then((r) => {
        setTools(r.tools);
        setSelectedTool(r.tools[0] ?? null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [selectedServerId]);

  const onInitialize = async () => {
    try {
      const cap = await initialize();
      setCapabilities(cap);
      message.success('MCP initialize 成功');
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    }
  };

  const onCall = async () => {
    if (!selectedServerId || !selectedTool) return;
    let parsed: Record<string, unknown> = {};
    try {
      parsed = argsText.trim() ? JSON.parse(argsText) : {};
    } catch (e) {
      message.error('arguments JSON 解析失败: ' + (e instanceof Error ? e.message : String(e)));
      return;
    }
    setCalling(true);
    try {
      const r = await callTool(selectedServerId, selectedTool.name, parsed);
      const text = r.content.map((c) => c.text).join('\n');
      setCallResult(`isError: ${r.isError}\n\n${text}`);
      message.success(r.isError ? '调用返回错误' : '调用成功');
    } catch (e) {
      setCallResult(`ERROR: ${e instanceof Error ? e.message : String(e)}`);
      message.error('调用失败');
    } finally {
      setCalling(false);
    }
  };

  const serverColumns: TableColumnsType<McpServer> = [
    { title: 'Server ID', dataIndex: 'id', key: 'id' },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: 'Transport', dataIndex: 'transport', key: 'transport', width: 130,
      render: (t: string) => <Tag>{t}</Tag> },
    {
      title: 'Protocol', dataIndex: 'protocolVersion', key: 'protocolVersion', width: 140,
    },
    { title: 'Endpoint', dataIndex: 'endpoint', key: 'endpoint', ellipsis: true },
  ];

  const toolColumns: TableColumnsType<McpTool> = [
    { title: '工具名', dataIndex: 'name', key: 'name', width: 200 },
    {
      title: '描述', dataIndex: 'description', key: 'description', ellipsis: true,
    },
    {
      title: '操作', key: 'actions', width: 80,
      render: (_: any, t: McpTool) => (
        <Button
          size="small" type="link"
          onClick={() => setSelectedTool(t)}
          disabled={selectedTool?.name === t.name}
        >
          选中
        </Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="协议"
        title="MCP 协议探索"
        sub="Model Context Protocol 客户端代理 · JSON-RPC 2.0"
        actions={
          <Space>
            <Button icon={<ApiOutlined />} onClick={onInitialize}>initialize</Button>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
          </Space>
        }
      />

      {capabilities && (
        <Alert
          message={`MCP 已初始化 · protocolVersion=${capabilities.protocolVersion}`}
          description={`server: ${capabilities.serverInfo?.name} v${capabilities.serverInfo?.version} · capabilities: ${JSON.stringify(capabilities.capabilities)}`}
          type="success"
          style={{ marginBottom: 16 }}
          closable
        />
      )}

      {error && <Alert type="error" message={error} closable style={{ marginBottom: 16 }} />}

      {servers.length === 0 && !loading ? (
        <EmptyState description="尚无 MCP server 注册" />
      ) : (
        <Tabs
          activeKey={selectedServerId ?? undefined}
          onChange={(k) => setSelectedServerId(k)}
          items={servers.map((s) => ({
            key: s.id,
            label: `${s.name} (${s.id})`,
            children: (
              <Row gutter={16}>
                <Col span={14}>
                  <Card title={`${s.name} · 工具`} size="small">
                    <Table<McpTool>
                      rowKey="name" columns={toolColumns} dataSource={tools}
                      size="small" pagination={false}
                    />
                  </Card>
                </Col>
                <Col span={10}>
                  <Card
                    title={selectedTool ? `调用 ${selectedTool.name}` : '调用工具'}
                    size="small"
                    extra={
                      <Button
                        type="primary" icon={<PlayCircleOutlined />}
                        disabled={!selectedTool}
                        loading={calling}
                        onClick={onCall}
                      >
                        调用
                      </Button>
                    }
                  >
                    {selectedTool && (
                      <>
                        <p style={{ color: 'var(--text-2)' }}>{selectedTool.description}</p>
                        <Input.TextArea
                          rows={3}
                          value={argsText}
                          onChange={(e) => {
                            setArgsText(e.target.value);
                            localStorage.setItem(ALLOWED_ARGS_KEY, e.target.value);
                          }}
                          style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                          placeholder='{"key": "value"}'
                        />
                        <p style={{ marginTop: 8, color: 'var(--text-3)', fontSize: 11 }}>
                          <CodeOutlined /> arguments JSON;空表示无参数
                        </p>
                      </>
                    )}
                  </Card>
                </Col>
              </Row>
            ),
          }))}
        />
      )}
    </>
  );
}
