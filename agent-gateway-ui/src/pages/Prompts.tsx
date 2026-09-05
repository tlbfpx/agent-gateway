import { useEffect, useState } from 'react';
import {
  Button, Form, Input, Modal, Select, Space, Table, Tag, message,
  type TableColumnsType,
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, DeleteOutlined, CodeOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import {
  listTemplates, getTemplate, createTemplate, deleteTemplate,
  addVersion, createExperiment, getExperimentSummary,
  type PromptTemplate, type PromptVersion, type PromptVariant,
} from '../lib/api/prompts';
import { useUrlState } from '../hooks/useUrlState';

const SELECTED_KEY = 'prompts.selected';

/**
 * /prompts Prompt 模板管理页（Round 12 #prompt-version §6 UI）。
 *
 * 列表 + 详情(版本树) + 创建 Template + 添加 Version + 创建 A/B Experiment + 查看 summary
 */
export function Prompts() {
  const [tenant, setTenant] = useUrlState<string>('tenant', 'au');
  const [templates, setTemplates] = useState<PromptTemplate[]>([]);
  const [selected, setSelected] = useState<PromptTemplate | null>(null);
  const [versions, setVersions] = useState<PromptVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [createOpen, setCreateOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [experimentOpen, setExperimentOpen] = useState(false);
  const [experimentSummary, setExperimentSummary] = useState<any | null>(null);
  const [createForm] = Form.useForm();
  const [versionForm] = Form.useForm();
  const [experimentForm] = Form.useForm();
  const [busy, setBusy] = useState(false);

  const reload = () => {
    setLoading(true);
    setError('');
    listTemplates(tenant)
      .then(setTemplates)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  };

  useEffect(reload, [tenant]);

  useEffect(() => {
    const saved = sessionStorage.getItem(SELECTED_KEY);
    if (saved) {
      try {
        const id = JSON.parse(saved) as number;
        if (id) loadSelected(id);
      } catch { /* noop */ }
    }
  }, []);

  const loadSelected = (id: number) => {
    getTemplate(id)
      .then((t) => {
        setSelected(t);
        setVersions(t.versions ?? []);
        sessionStorage.setItem(SELECTED_KEY, String(id));
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  };

  const onCreateTemplate = async () => {
    try {
      const v = await createForm.validateFields();
      setBusy(true);
      const t = await createTemplate({ ...v, tenantId: tenant });
      message.success(`已创建 ${t.name}`);
      setCreateOpen(false);
      createForm.resetFields();
      reload();
      loadSelected(t.id);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const onAddVersion = async () => {
    if (!selected) return;
    try {
      const v = await versionForm.validateFields();
      setBusy(true);
      const version = await addVersion(selected.id, {
        systemPrompt: v.systemPrompt,
        userPrompt: v.userPrompt,
        model: v.model,
        authorId: v.authorId,
        params: {},
      });
      message.success(`已添加 v${version.version}`);
      setVersionOpen(false);
      versionForm.resetFields();
      loadSelected(selected.id);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const onCreateExperiment = async () => {
    if (!selected) return;
    try {
      const v = await experimentForm.validateFields();
      setBusy(true);
      // 默认 50/50 split
      const variants: PromptVariant[] = versions.slice(0, 2).map((ver, i) => ({
        versionId: ver.id,
        weight: 50,
        label: i === 0 ? 'control' : 'treatment',
      }));
      if (variants.length < 2) {
        message.error('需要至少 2 个 version 才能创建 A/B 实验');
        return;
      }
      const exp = await createExperiment(selected.id, {
        name: v.name,
        tenantId: tenant,
        createdBy: v.createdBy,
        variants,
      });
      message.success(`实验 #${exp.id} 已创建`);
      setExperimentOpen(false);
      experimentForm.resetFields();
      // 立即拉 summary
      const sum = await getExperimentSummary(exp.id);
      setExperimentSummary(sum);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const templateColumns: TableColumnsType<PromptTemplate> = [
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: 'Owner', dataIndex: 'ownerId', key: 'ownerId', width: 90 },
    {
      title: '标签', dataIndex: 'tags', key: 'tags',
      render: (tags: string[]) => tags?.map((t) => <Tag key={t}>{t}</Tag>) ?? null,
    },
    {
      title: '更新于', dataIndex: 'updatedAt', key: 'updatedAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_: any, t: PromptTemplate) => (
        <Button size="small" type="link" onClick={() => loadSelected(t.id)}>查看</Button>
      ),
    },
  ];

  const versionColumns: TableColumnsType<PromptVersion> = [
    { title: 'v', dataIndex: 'version', key: 'version', width: 60 },
    { title: '模型', dataIndex: 'model', key: 'model', width: 110 },
    {
      title: 'User Prompt 摘要', dataIndex: 'userPrompt', key: 'userPrompt',
      ellipsis: true,
      render: (s: string) => (s ? s.slice(0, 80) + (s.length > 80 ? '…' : '') : '—'),
    },
    { title: '作者', dataIndex: 'authorId', key: 'authorId', width: 90 },
    {
      title: '创建于', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="智能"
        title="Prompt 模板"
        sub="版本管理 + A/B 实验"
        actions={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建模板
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
        <Table<PromptTemplate>
          rowKey="id" columns={templateColumns} dataSource={templates}
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
                <Button icon={<CodeOutlined />} onClick={() => setVersionOpen(true)}>
                  添加版本
                </Button>
                <Button icon={<ExperimentOutlined />} onClick={() => setExperimentOpen(true)}>
                  创建 A/B 实验
                </Button>
                <Button danger icon={<DeleteOutlined />}
                  onClick={async () => {
                    if (!confirm(`确认删除模板 "${selected.name}" 及其所有版本？`)) return;
                    try {
                      await deleteTemplate(selected.id);
                      message.success('已删除');
                      setSelected(null);
                      setVersions([]);
                      sessionStorage.removeItem(SELECTED_KEY);
                      reload();
                    } catch (e) {
                      message.error(e instanceof Error ? e.message : String(e));
                    }
                  }}
                >删除模板</Button>
              </Space>
            }
          />
          <Table<PromptVersion>
            rowKey="id" columns={versionColumns} dataSource={versions}
            size="middle" pagination={false}
          />
          {experimentSummary && (
            <div style={{ marginTop: 24 }}>
              <PageHeader
                eyebrow="实验"
                title={`实验 #${experimentSummary.experimentId}`}
                sub={`总成功率 ${(experimentSummary.successRate * 100).toFixed(1)}% (${experimentSummary.success}/${experimentSummary.total})`}
              />
              <Table
                rowKey="versionId" size="middle" pagination={false}
                dataSource={experimentSummary.byVariant}
                columns={[
                  { title: 'Version', dataIndex: 'versionId', width: 100 },
                  { title: '总数', dataIndex: 'total', width: 100 },
                  { title: '成功', dataIndex: 'success', width: 100 },
                  {
                    title: '成功率', dataIndex: 'successRate', width: 150,
                    render: (r: number) => `${(r * 100).toFixed(1)}%`,
                  },
                ]}
              />
            </div>
          )}
        </>
      )}

      <Modal
        title="新建 Prompt 模板"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreateTemplate}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={createForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="summarize-text" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="一句话说明用途" />
          </Form.Item>
          <Form.Item name="ownerId" label="Owner ID" rules={[{ required: true, message: '请输入 Owner' }]}>
            <Input type="number" placeholder="1" />
          </Form.Item>
          <Form.Item name="tags" label="标签(逗号分隔)">
            <Input placeholder="nlp, summary, prod" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`为 ${selected?.name} 添加版本`}
        open={versionOpen}
        onCancel={() => setVersionOpen(false)}
        onOk={onAddVersion}
        confirmLoading={busy}
        okText="添加"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={versionForm} layout="vertical">
          <Form.Item name="systemPrompt" label="System Prompt">
            <Input.TextArea rows={2} placeholder="You are a helpful assistant." />
          </Form.Item>
          <Form.Item name="userPrompt" label="User Prompt" rules={[{ required: true }]}>
            <Input.TextArea rows={3} placeholder="Summarize: {{text}}" />
          </Form.Item>
          <Form.Item name="model" label="模型">
            <Select
              placeholder="选择模型"
              options={[
                { value: 'gpt-4o', label: 'gpt-4o' },
                { value: 'gpt-4o-mini', label: 'gpt-4o-mini' },
                { value: 'claude-3.5-sonnet', label: 'claude-3.5-sonnet' },
                { value: 'deepseek-chat', label: 'deepseek-chat' },
              ]}
            />
          </Form.Item>
          <Form.Item name="authorId" label="作者 ID" rules={[{ required: true }]}>
            <Input type="number" placeholder="1" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`为 ${selected?.name} 创建 A/B 实验`}
        open={experimentOpen}
        onCancel={() => setExperimentOpen(false)}
        onOk={onCreateExperiment}
        confirmLoading={busy}
        okText="创建"
        cancelText="取消"
        destroyOnClose
      >
        <p style={{ color: 'var(--text-2)' }}>
          默认按 50/50 切分最近两个版本；可通过 API 调整权重。
        </p>
        <Form form={experimentForm} layout="vertical">
          <Form.Item name="name" label="实验名" rules={[{ required: true }]}>
            <Input placeholder="v1-vs-v2-quality" />
          </Form.Item>
          <Form.Item name="createdBy" label="创建者 ID" rules={[{ required: true }]}>
            <Input type="number" placeholder="1" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
