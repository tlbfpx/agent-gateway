import { useEffect, useState } from 'react';
import {
  Alert, Button, Card, Col, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, ExperimentOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { useUrlState } from '../hooks/useUrlState';
import {
  listGateways, applyGateway, deleteGateway, reconcileGateway,
  listRoutes, applyRoute, deleteRoute,
  type K8sGateway, type K8sRoute, type K8sReconcileResult,
} from '../lib/api/k8s';

/**
 * /k8s K8s CRD 探索页（Round 14 #k8s-crd §7 UI）。
 *
 * Gateway + Route 管理,模拟 kubectl apply 体验。
 */
export function K8sGateways() {
  const [namespace, setNamespace] = useUrlState<string>('namespace', 'default');
  const [gateways, setGateways] = useState<K8sGateway[]>([]);
  const [routes, setRoutes] = useState<K8sRoute[]>([]);
  const [selected, setSelected] = useState<K8sGateway | null>(null);
  const [reconcileResult, setReconcileResult] = useState<K8sReconcileResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [createOpen, setCreateOpen] = useState(false);
  const [routeOpen, setRouteOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [routeForm] = Form.useForm();
  const [busy, setBusy] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    Promise.all([listGateways(namespace), listRoutes(namespace)])
      .then(([gs, rs]) => {
        setGateways(gs.items);
        setRoutes(rs.items);
        if (gs.items.length > 0 && !selected) setSelected(gs.items[0]);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, [namespace]);

  const onReconcile = async () => {
    if (!selected) return;
    try {
      const r = await reconcileGateway(namespace, selected.metadata.name);
      setReconcileResult(r);
      message.success('已 reconcile');
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    }
  };

  const onCreateGateway = async () => {
    try {
      const v = await createForm.validateFields();
      setBusy(true);
      const gw: K8sGateway = {
        apiVersion: 'gateway.agentgateway.io/v1alpha1',
        kind: 'AgentGateway',
        metadata: { name: v.name, namespace },
        spec: {
          listeners: [{ name: 'http', port: v.port, protocol: 'HTTP', tls: false }],
          replicas: v.replicas,
        },
      };
      await applyGateway(namespace, gw);
      message.success('已创建');
      setCreateOpen(false);
      createForm.resetFields();
      reload();
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally { setBusy(false); }
  };

  const onCreateRoute = async () => {
    if (!selected) return;
    try {
      const v = await routeForm.validateFields();
      setBusy(true);
      const route: K8sRoute = {
        apiVersion: 'gateway.agentgateway.io/v1alpha1',
        kind: 'AgentRoute',
        metadata: { name: v.name, namespace },
        spec: {
          gatewayRef: selected.metadata.name,
          match: [{ path: v.path, method: v.method }],
          backends: [{ provider: v.provider, weight: v.weight, model: v.model }],
        },
      };
      await applyRoute(namespace, route);
      message.success('已创建');
      setRouteOpen(false);
      routeForm.resetFields();
      reload();
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally { setBusy(false); }
  };

  const gwColumns: TableColumnsType<K8sGateway> = [
    { title: 'Name', key: 'name', render: (_, g) => g.metadata.name },
    {
      title: 'Listeners',
      key: 'listeners',
      render: (_, g) => g.spec.listeners.map(l =>
        <Tag key={l.name}>{l.name}:{l.port}</Tag>,
      ),
    },
    { title: 'Replicas', dataIndex: ['spec', 'replicas'], key: 'replicas', width: 100 },
    {
      title: 'Status', key: 'status', width: 100,
      render: (_, g) => {
        const ready = g.status?.conditions?.find(c => c.type === 'Ready');
        return ready ? <Tag color="green">{ready.status}</Tag> : <Tag>Unknown</Tag>;
      },
    },
  ];

  const routeColumns: TableColumnsType<K8sRoute> = [
    { title: 'Name', key: 'name', render: (_, r) => r.metadata.name },
    { title: 'Gateway', key: 'gw', render: (_, r) => r.spec.gatewayRef, width: 150 },
    {
      title: 'Match', key: 'match',
      render: (_, r) => r.spec.match.map((m, i) =>
        <Tag key={i}>{m.method ?? 'ANY'} {m.path}</Tag>,
      ),
    },
    {
      title: 'Backends', key: 'backends',
      render: (_, r) => r.spec.backends.map((b, i) =>
        <Tag key={i} color="blue">{b.provider}/{b.model ?? '*'}/{b.weight}</Tag>,
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="平台化"
        title="K8s CRD"
        sub="AgentGateway / AgentRoute CRD 管理(模拟 K8s API)"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建 Gateway
            </Button>
          </Space>
        }
      />

      <Space style={{ marginBottom: 16 }}>
        <span>Namespace:</span>
        <Input style={{ width: 200 }} value={namespace} onChange={(e) => setNamespace(e.target.value)} />
      </Space>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}

      {gateways.length === 0 && !loading ? (
        <EmptyState description="尚无 Gateway" />
      ) : (
        <Card title={`Gateways (${gateways.length})`} size="small">
          <Table<K8sGateway>
            rowKey={g => g.metadata.name}
            columns={gwColumns} dataSource={gateways}
            size="small" pagination={false}
            onRow={(r) => ({ onClick: () => setSelected(r) })}
            rowClassName={(r) => selected?.metadata.name === r.metadata.name ? 'ant-table-row-selected' : ''}
          />
        </Card>
      )}

      {selected && (
        <>
          <PageHeader
            eyebrow="详情"
            title={selected.metadata.name}
            sub={`namespace=${selected.metadata.namespace}`}
            actions={
              <Space>
                <Button icon={<ExperimentOutlined />} onClick={onReconcile}>
                  reconcile
                </Button>
                <Button danger icon={<DeleteOutlined />}
                  onClick={async () => {
                    if (!confirm(`确认删除 Gateway "${selected.metadata.name}"?`)) return;
                    await deleteGateway(namespace, selected.metadata.name);
                    message.success('已删除');
                    setSelected(null);
                    setReconcileResult(null);
                    reload();
                  }}
                >删除</Button>
              </Space>
            }
          />
          <Row gutter={16}>
            <Col span={12}>
              <Card title={`Routes (${routes.filter(r => r.spec.gatewayRef === selected.metadata.name).length})`} size="small">
                <Button size="small" icon={<PlusOutlined />} onClick={() => setRouteOpen(true)}>
                  添加 Route
                </Button>
                <Table<K8sRoute>
                  rowKey={r => r.metadata.name}
                  columns={routeColumns}
                  dataSource={routes.filter(r => r.spec.gatewayRef === selected.metadata.name)}
                  size="small" pagination={{ pageSize: 10 }}
                />
              </Card>
            </Col>
            <Col span={12}>
              {reconcileResult && (
                <Card title="Reconcile Result" size="small">
                  <pre style={{ background: 'var(--bg-sunken)', padding: 12, borderRadius: 4, fontSize: 11 }}>
                    {JSON.stringify(reconcileResult, null, 2)}
                  </pre>
                </Card>
              )}
            </Col>
          </Row>
        </>
      )}

      <Modal
        title="新建 Gateway"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreateGateway}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical" initialValues={{ port: 8080, replicas: 1 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="prod-gw" />
          </Form.Item>
          <Form.Item name="port" label="HTTP 端口" rules={[{ required: true }]}>
            <InputNumber min={1} max={65535} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="replicas" label="Replicas">
            <InputNumber min={1} max={10} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建 Route"
        open={routeOpen}
        onCancel={() => setRouteOpen(false)}
        onOk={onCreateRoute}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={routeForm} layout="vertical" initialValues={{ method: 'POST', weight: 100, provider: 'openai' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="chat-route" />
          </Form.Item>
          <Form.Item name="path" label="路径" rules={[{ required: true }]}>
            <Input placeholder="/v1/chat" />
          </Form.Item>
          <Form.Item name="method" label="方法">
            <Select options={['GET', 'POST', 'PUT', 'DELETE', 'ANY'].map(m => ({ value: m, label: m }))} />
          </Form.Item>
          <Form.Item name="provider" label="Provider">
            <Input />
          </Form.Item>
          <Form.Item name="model" label="模型">
            <Input placeholder="gpt-4o" />
          </Form.Item>
          <Form.Item name="weight" label="权重(1-100)">
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}