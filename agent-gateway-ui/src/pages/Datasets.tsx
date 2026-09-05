import { useEffect, useState } from 'react';
import {
  Button, Form, Input, Modal, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, ExperimentOutlined,
  CloudUploadOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { useUrlState } from '../hooks/useUrlState';
import {
  listDatasets, createDataset, deleteDataset, importCases, listCases,
  runEval, listRuns,
  type EvalDataset, type EvalRun, type EvalStrategy,
} from '../lib/api/datasets';

const SAMPLE_JSONL = `{"input":"hello","expected":"Hello! How can I help?"}
{"input":"2+2","expected":"4"}
{"input":"capital of France","expected":"Paris"}`;

/**
 * /datasets 数据集 + 评测管理页（Round 13 §dataset-eval §7 UI）。
 */
export function Datasets() {
  const [tenant, setTenant] = useUrlState<string>('tenant', 'au');
  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [selected, setSelected] = useState<EvalDataset | null>(null);
  const [cases, setCases] = useState<any[]>([]);
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [runOpen, setRunOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [runForm] = Form.useForm();
  const [importText, setImportText] = useState(SAMPLE_JSONL);
  const [busy, setBusy] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    listDatasets(tenant)
      .then(setDatasets)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, [tenant]);

  const loadSelected = async (id: number) => {
    try {
      const d = await (await import('../lib/api/datasets')).getDataset(id);
      setSelected(d);
      const [cs, rs] = await Promise.all([listCases(id), listRuns(id)]);
      setCases(cs);
      setRuns(rs);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const onCreate = async () => {
    try {
      const v = await createForm.validateFields();
      setBusy(true);
      const d = await createDataset({ ...v, tenantId: tenant });
      message.success(`已创建 ${d.name}`);
      setCreateOpen(false);
      createForm.resetFields();
      reload();
      loadSelected(d.id);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally { setBusy(false); }
  };

  const onImport = async () => {
    if (!selected) return;
    try {
      setBusy(true);
      const out = await importCases(selected.id, importText);
      message.success(`已导入 ${out.imported} 条`);
      setImportOpen(false);
      loadSelected(selected.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  };

  const onRun = async () => {
    if (!selected) return;
    try {
      const v = await runForm.validateFields();
      setBusy(true);
      const run = await runEval(selected.id, {
        promptVersionId: v.promptVersionId,
        model: v.model,
        strategy: v.strategy as EvalStrategy,
        triggeredBy: v.triggeredBy,
      });
      message.success(`评测 #${run.id} 完成 · 通过率 ${(run.metrics.passRate * 100).toFixed(1)}%`);
      setRunOpen(false);
      runForm.resetFields();
      loadSelected(selected.id);
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e));
    } finally { setBusy(false); }
  };

  const datasetColumns: TableColumnsType<EvalDataset> = [
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: 'Owner', dataIndex: 'ownerId', key: 'ownerId', width: 90 },
    {
      title: '标签', dataIndex: 'tags', key: 'tags',
      render: (tags: string[]) => tags?.map((t) => <Tag key={t}>{t}</Tag>) ?? null,
    },
    {
      title: '创建于', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: any, d: EvalDataset) => (
        <Button size="small" type="link" onClick={() => loadSelected(d.id)}>查看</Button>
      ),
    },
  ];

  const caseColumns: TableColumnsType<any> = [
    { title: '#', dataIndex: 'id', key: 'id', width: 60 },
    { title: 'Input', dataIndex: 'input', key: 'input', ellipsis: true },
    { title: 'Expected', dataIndex: 'expectedOutput', key: 'expectedOutput', ellipsis: true },
    { title: '权重', dataIndex: 'weight', key: 'weight', width: 80 },
  ];

  const runColumns: TableColumnsType<EvalRun> = [
    { title: 'Run ID', dataIndex: 'id', key: 'id', width: 80 },
    { title: '模型', dataIndex: 'model', key: 'model', width: 110 },
    {
      title: '策略', dataIndex: 'strategy', key: 'strategy', width: 90,
      render: (s: string) => <Tag>{s}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (s: string) => (
        <Tag color={s === 'COMPLETED' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag>
      ),
    },
    {
      title: '通过率', key: 'passRate', width: 150,
      render: (_: any, r: EvalRun) => (
        <span style={{ color: r.metrics.passRate >= 0.8 ? 'green' : r.metrics.passRate >= 0.5 ? 'orange' : 'red' }}>
          {(r.metrics.passRate * 100).toFixed(1)}% ({r.metrics.passed}/{r.metrics.total})
        </span>
      ),
    },
    {
      title: '平均延迟', key: 'avgLatency', width: 120,
      render: (_: any, r: EvalRun) => `${r.metrics.avgLatencyMs.toFixed(0)} ms`,
    },
    {
      title: '创建于', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="数据闭环"
        title="数据集 / 评测"
        sub="JSONL 导入 + 规则评测 + 通过率报告"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建数据集
            </Button>
          </Space>
        }
      />

      <Space style={{ marginBottom: 16 }}>
        <span>租户:</span>
        <Input style={{ width: 120 }} value={tenant} onChange={(e) => setTenant(e.target.value)} />
      </Space>

      {error ? (
        <EmptyState description={`加载失败: ${error}`} />
      ) : (
        <Table<EvalDataset>
          rowKey="id" columns={datasetColumns} dataSource={datasets}
          loading={loading} size="middle" pagination={{ pageSize: 20 }}
        />
      )}

      {selected && (
        <>
          <PageHeader
            eyebrow="详情"
            title={selected.name}
            sub={selected.description || '(无描述)'}
            actions={
              <Space>
                <Button icon={<CloudUploadOutlined />} onClick={() => setImportOpen(true)}>
                  导入 JSONL
                </Button>
                <Button icon={<ExperimentOutlined />} onClick={() => setRunOpen(true)}>
                  跑评测
                </Button>
                <Button danger icon={<DeleteOutlined />}
                  onClick={async () => {
                    if (!confirm(`确认删除数据集 "${selected.name}" 及其所有 cases/runs？`)) return;
                    try {
                      await deleteDataset(selected.id);
                      message.success('已删除');
                      setSelected(null);
                      setCases([]);
                      setRuns([]);
                      reload();
                    } catch (e) {
                      message.error(e instanceof Error ? e.message : String(e));
                    }
                  }}
                >删除</Button>
              </Space>
            }
          />
          <h3 style={{ marginTop: 16 }}>Cases ({cases.length})</h3>
          <Table
            rowKey="id" columns={caseColumns} dataSource={cases}
            size="small" pagination={{ pageSize: 20 }}
          />
          <h3 style={{ marginTop: 24 }}>评测历史 ({runs.length})</h3>
          <Table<EvalRun>
            rowKey="id" columns={runColumns} dataSource={runs}
            size="small" pagination={{ pageSize: 20 }}
          />
        </>
      )}

      <Modal
        title="新建数据集"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreate}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input placeholder="regression-suite" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="smoke test" />
          </Form.Item>
          <Form.Item name="ownerId" label="Owner ID" rules={[{ required: true }]}>
            <Input type="number" placeholder="1" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`导入 JSONL 到 ${selected?.name ?? ''}`}
        open={importOpen}
        onCancel={() => setImportOpen(false)}
        onOk={onImport}
        confirmLoading={busy}
        okText="导入"
        cancelText="取消"
        width={720}
        destroyOnClose
      >
        <p style={{ color: 'var(--text-2)', marginBottom: 8 }}>
          每行一个 JSON: <code>{"{ \"input\": \"...\", \"expected\": \"...\", \"weight\": 1 }"}</code>
        </p>
        <Input.TextArea
          rows={12} value={importText} onChange={(e) => setImportText(e.target.value)}
          style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
        />
      </Modal>

      <Modal
        title={`为 ${selected?.name ?? ''} 跑评测`}
        open={runOpen}
        onCancel={() => setRunOpen(false)}
        onOk={onRun}
        confirmLoading={busy}
        okText="运行"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={runForm} layout="vertical">
          <Form.Item name="promptVersionId" label="Prompt Version ID" rules={[{ required: true }]}>
            <Input type="number" placeholder="1" />
          </Form.Item>
          <Form.Item name="model" label="模型" rules={[{ required: true }]} initialValue="gpt-4o">
            <Select
              options={[
                { value: 'gpt-4o', label: 'gpt-4o' },
                { value: 'gpt-4o-mini', label: 'gpt-4o-mini' },
                { value: 'claude-3.5-sonnet', label: 'claude-3.5-sonnet' },
              ]}
            />
          </Form.Item>
          <Form.Item name="strategy" label="评测策略" rules={[{ required: true }]} initialValue="CONTAINS">
            <Select
              options={[
                { value: 'EXACT', label: 'EXACT - 精确匹配(忽略大小写)' },
                { value: 'CONTAINS', label: 'CONTAINS - 包含子串' },
                { value: 'REGEX', label: 'REGEX - 正则匹配' },
              ]}
            />
          </Form.Item>
          <Form.Item name="triggeredBy" label="触发者 ID" rules={[{ required: true }]} initialValue={1}>
            <Input type="number" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
