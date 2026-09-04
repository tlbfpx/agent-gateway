import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Form, Input, Row, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PauseCircleOutlined, ReloadOutlined, ExperimentOutlined,
  ApiOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import {
  listPlugins, disablePlugin, reloadPlugins, testSandbox,
  type PluginDescriptor, type PluginResponse,
} from '../lib/api/plugins';

const CAPABILITY_COLORS: Record<string, string> = {
  HEADER_INJECT: 'blue',
  BODY_TRANSFORM: 'cyan',
  RATE_LIMIT: 'orange',
  AUDIT: 'purple',
  COMPRESS: 'green',
  LOG: 'default',
};

/**
 * /plugins 插件管理 + 沙箱测试页（Round 15 §wasm-plugins §9 UI）。
 */
export function Plugins() {
  const [plugins, setPlugins] = useState<PluginDescriptor[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [testResult, setTestResult] = useState<PluginResponse | null>(null);
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    listPlugins()
      .then(setPlugins)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, []);

  const onDisable = async (id: string) => {
    if (!confirm(`确认禁用插件 "${id}"?`)) return;
    try {
      await disablePlugin(id);
      message.success('已禁用');
      reload();
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    }
  };

  const onReload = async () => {
    try {
      const r = await reloadPlugins();
      message.success(`已重载 ${r.total} 个插件`);
      reload();
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    }
  };

  const onTest = async () => {
    try {
      const v = await form.validateFields();
      setBusy(true);
      const r = await testSandbox({
        path: v.path,
        method: v.method,
        body: v.body,
        tenant: v.tenant,
        headers: { 'X-Test': 'demo' },
      });
      setTestResult(r);
      message.success(r.blocked ? '请求被插件阻断' : '沙箱执行成功');
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const columns: TableColumnsType<PluginDescriptor> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 220 },
    {
      title: '名称', dataIndex: 'name', key: 'name',
      render: (n: string, p) => (
        <span>
          {n}{' '}
          {p.builtin && <Tag color="default" style={{ marginLeft: 4 }}>built-in</Tag>}
        </span>
      ),
    },
    { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
    {
      title: 'Format', dataIndex: 'format', key: 'format', width: 90,
      render: (f: string) => <Tag>{f}</Tag>,
    },
    {
      title: '能力', dataIndex: 'capabilities', key: 'capabilities',
      render: (caps: string[]) => (
        <Space wrap size={4}>
          {caps.map((c) => <Tag key={c} color={CAPABILITY_COLORS[c]}>{c}</Tag>)}
        </Space>
      ),
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_, p) => (
        <Button
          size="small" danger icon={<PauseCircleOutlined />}
          onClick={() => onDisable(p.id)}
        >禁用</Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="扩展"
        title="插件系统"
        sub="Java SPI + 4 官方样本插件(R15+2 swap Chicory Wasm)"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={onReload}>重载</Button>
            <Button icon={<ExperimentOutlined />} onClick={reload}>刷新</Button>
          </Space>
        }
      />

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}

      {plugins.length === 0 && !loading ? (
        <EmptyState description="尚无插件" />
      ) : (
        <Card title={`已注册插件 (${plugins.length})`} size="small">
          <Table<PluginDescriptor>
            rowKey="id" columns={columns} dataSource={plugins}
            size="middle" pagination={false}
          />
        </Card>
      )}

      <Row gutter={16} style={{ marginTop: 24 }}>
        <Col span={12}>
          <Card title="沙箱测试" size="small" extra={<ApiOutlined />}>
            <Form form={form} layout="vertical" initialValues={{
              path: '/v1/chat/completions', method: 'POST', body: '{"messages":[]}',
              tenant: 'au',
            }}>
              <Form.Item name="path" label="Path" rules={[{ required: true }]}>
                <Input placeholder="/v1/chat/completions" />
              </Form.Item>
              <Form.Item name="method" label="Method">
                <Select options={['GET', 'POST', 'PUT', 'DELETE'].map(m => ({ value: m, label: m }))} />
              </Form.Item>
              <Form.Item name="body" label="Body">
                <Input.TextArea rows={2} />
              </Form.Item>
              <Form.Item name="tenant" label="Tenant">
                <Input placeholder="au" />
              </Form.Item>
              <Button type="primary" icon={<ExperimentOutlined />} loading={busy} onClick={onTest} block>
                运行沙箱
              </Button>
            </Form>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="沙箱结果" size="small">
            {testResult ? (
              <pre style={{ background: 'var(--bg-sunken)', padding: 12, borderRadius: 4, fontSize: 11 }}>
{JSON.stringify(testResult, null, 2)}
              </pre>
            ) : (
              <EmptyState description='点击"运行沙箱"查看响应' />
            )}
          </Card>
        </Col>
      </Row>
    </>
  );
}