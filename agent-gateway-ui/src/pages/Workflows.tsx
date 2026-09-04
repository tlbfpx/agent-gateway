/**
 * Workflows — 显式多 Agent 编排管理 UI(spec C1 §8 + 扩展 P0)
 *
 * 两个 Tabs:
 *   1. 运行历史 — 列表(workflowName/status/range 过滤)+ 单 run 详情
 *   2. 定义库 — CRUD workflow 定义(可复用)
 *
 * 运行时支持 definitionName 引用已存定义;落地持久化走 PG(InMemory 降级)。
 */
import { useEffect, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Table,
  Tabs,

  Typography,
  message,
} from 'antd';
import { PageHeader } from '../components/framework/PageHeader';
import { EmptyState } from '../components/framework/EmptyState';
import { Tag, Tooltip, Drawer, Descriptions } from 'antd';
import {
  DeleteOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  deleteDefinition,
  getDefinition,
  getWorkflowRun,
  listDefinitions,
  listWorkflowRuns,
  runWorkflowJson,
  saveDefinitionJson,
  updateDefinition,
  type RunListItem,
  type StepRun,
  type WorkflowDefinition,
  type WorkflowRun,
  type WorkflowStatus,
} from '../lib/api/workflows';

const { Text } = Typography;

const STATUS_COLOR: Record<WorkflowStatus, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
};

const STEP_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing',
  COMPLETED: 'success',
  FAILED: 'error',
};

const SAMPLE_DEF = JSON.stringify(
  {
    name: 'rag-summary',
    steps: [
      { name: 'retrieve', agent: 'rag-agent', inputs: { query: '$.inputs.question' } },
      { name: 'summarize', agent: 'summarizer-agent', inputs: { context: '$.steps.retrieve.outputs.chunks' } },
    ],
  },
  null,
  2,
);

const SAMPLE_INPUTS = '{"question": "hello"}';

function fmtTime(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString('zh-CN', { hour12: false });
}

function fmtDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

export function Workflows() {
  return (
    <>
      <PageHeader
        eyebrow="Workflows · 编排"
        title="多 Agent 链式工作流"
        sub="显式编排(不走 LLM 自决):Step 链 + JSONPath 引用上一步 outputs"
      />
      <Tabs
        defaultActiveKey="runs"
        items={[
          { key: 'runs', label: '运行历史', children: <RunsTab /> },
          { key: 'definitions', label: '定义库', children: <DefinitionsTab /> },
        ]}
      />
    </>
  );
}

// ==================== 运行历史 Tab ====================

function RunsTab() {
  const [name, setName] = useState<string | undefined>();
  const [status, setStatus] = useState<WorkflowStatus | undefined>();
  const [range, setRange] = useState('24h');
  const [runs, setRuns] = useState<RunListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<WorkflowRun | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  // 简易运行入口(与 Definitions Tab 独立,允许直接粘 definitionJson 跑)
  const [defJson, setDefJson] = useState(SAMPLE_DEF);
  const [inputsJson, setInputsJson] = useState(SAMPLE_INPUTS);
  const [running, setRunning] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const data = await listWorkflowRuns({ workflowName: name, status, range, limit: 50 });
      setRuns(data);
    } catch (e) {
      message.error('加载运行历史失败: ' + (e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);  // eslint-disable-line react-hooks/exhaustive-deps

  const onRun = async () => {
    let def: unknown;
    let inputs: Record<string, unknown> = {};
    try { def = JSON.parse(defJson); } catch (e) {
      message.error('definition JSON 解析失败: ' + (e as Error).message); return;
    }
    try { if (inputsJson.trim()) inputs = JSON.parse(inputsJson); } catch (e) {
      message.error('inputs JSON 解析失败: ' + (e as Error).message); return;
    }
    setRunning(true);
    try {
      const run = await runWorkflowJson({ definitionJson: JSON.stringify(def), inputs });
      message.success(`运行 ${run.runId.slice(0, 12)}… 状态: ${run.status}`);
      setSelected(run);
      void load();
    } catch (e) {
      message.error('运行失败: ' + (e as Error).message);
    } finally {
      setRunning(false);
    }
  };

  const onSelectRun = async (runId: string) => {
    setLoadingDetail(true);
    try {
      const fresh = await getWorkflowRun(runId);
      setSelected(fresh);
    } catch (e) {
      message.error('加载详情失败: ' + (e as Error).message);
    } finally {
      setLoadingDetail(false);
    }
  };

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={10}>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Card title="筛选" size="small">
            <Space wrap>
              <Select
                allowClear
                placeholder="工作流"
                style={{ width: 160 }}
                value={name}
                onChange={setName}
                options={[...new Set(runs.map((r) => r.workflowName))].map((v) => ({ value: v, label: v }))}
              />
              <Select
                allowClear
                placeholder="状态"
                style={{ width: 120 }}
                value={status}
                onChange={setStatus}
                options={[
                  { value: 'RUNNING', label: 'RUNNING' },
                  { value: 'COMPLETED', label: 'COMPLETED' },
                  { value: 'FAILED', label: 'FAILED' },
                ]}
              />
              <Select
                style={{ width: 120 }}
                value={range}
                onChange={setRange}
                options={[
                  { value: '1h', label: '最近 1h' },
                  { value: '6h', label: '最近 6h' },
                  { value: '24h', label: '最近 24h' },
                  { value: '7d', label: '最近 7d' },
                ]}
              />
              <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
            </Space>
          </Card>

          <Card title="运行历史(逆序)" size="small" styles={{ body: { padding: 0 } }}>
            <Table<RunListItem>
              rowKey="runId"
              size="small"
              dataSource={runs}
              loading={loading}
              pagination={{ pageSize: 10, showSizeChanger: false }}
              onRow={(r) => ({ onClick: () => void onSelectRun(r.runId), style: { cursor: 'pointer' } })}
              locale={{ emptyText: <EmptyState variant="no-data" description="还没有运行" /> }}
              columns={[
                { title: 'Run ID', dataIndex: 'runId', width: 110,
                  render: (v: string) => <code style={{ fontSize: 11 }}>{v.slice(0, 8)}…</code> },
                { title: '工作流', dataIndex: 'workflowName', width: 130 },
                { title: '状态', dataIndex: 'status', width: 90,
                  render: (s: WorkflowStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag> },
                { title: '开始', dataIndex: 'startedAt', width: 90,
                  render: (v: string) => <span style={{ fontSize: 11 }}>{fmtTime(v)}</span> },
              ]}
            />
          </Card>

          <Card title="快速运行(粘 JSON)" size="small">
            <Text type="secondary" style={{ fontSize: 11 }}>WorkflowDef JSON:</Text>
            <Input.TextArea
              rows={6} value={defJson} onChange={(e) => setDefJson(e.target.value)}
              style={{ fontFamily: 'monospace', fontSize: 11, marginTop: 4 }} spellCheck={false}
            />
            <Text type="secondary" style={{ fontSize: 11 }}>inputs (JSON):</Text>
            <Input
              value={inputsJson} onChange={(e) => setInputsJson(e.target.value)}
              style={{ fontFamily: 'monospace', fontSize: 11, marginTop: 4 }} spellCheck={false}
            />
            <Button type="primary" icon={<PlayCircleOutlined />} onClick={onRun} loading={running} style={{ marginTop: 8 }}>
              运行
            </Button>
          </Card>
        </Space>
      </Col>

      <Col xs={24} lg={14}>
        <Card title="运行详情" size="small" loading={loadingDetail}>
          {selected ? <RunDetail run={selected} /> : <EmptyState variant="no-data" description="选择左侧一行查看详情" />}
        </Card>
      </Col>
    </Row>
  );
}

function RunDetail({ run }: { run: WorkflowRun }) {
  // 选中 step(时间轴点击 → Drawer 详情)
  const [selectedStep, setSelectedStep] = useState<StepRun | null>(null);
  // C2 parallel + C4 嵌套可视化:parentIndex >= 0 表示子节点(parallel 分支 / switch case 子步骤),
  // branchName 是嵌套链路 tag(如 "sw>case:value=a>b1"),同 tag 归一组嵌套块
  const groups = new Map<number, StepRun[]>();
  const topLevel: StepRun[] = [];
  for (const s of run.steps) {
    if (s.parentIndex != null && s.parentIndex >= 0) {
      const arr = groups.get(s.parentIndex) ?? [];
      arr.push(s);
      groups.set(s.parentIndex, arr);
    } else {
      topLevel.push(s);
    }
  }

  // 时间轴:计算各 step 的相对宽度(按 durationMs / 总耗时,运行时由 startedAt 推算;
  // 由于 WorkflowRun 没存 startedAt per step — 我们用 durationMs 作为 bar 宽度,
  // 总耗时 = run.finishedAt - run.startedAt;如果 finishedAt=null 取各 step duration 之和)
  const totalMs = (() => {
    if (run.startedAt && run.finishedAt) {
      const a = new Date(run.startedAt).getTime();
      const b = new Date(run.finishedAt).getTime();
      const d = b - a;
      return d > 0 ? d : null;
    }
    return null;
  })();

  return (
    <div>
      <Space size={8} wrap>
        <Tag color={STATUS_COLOR[run.status]} style={{ fontWeight: 600 }}>{run.status}</Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>{run.workflowName}</Text>
        <Tag style={{ fontFamily: 'monospace' }}>{run.runId.slice(0, 12)}…</Tag>
        {groups.size > 0 && (() => {
          // 区分 C2 顶层 parallel 分支与 C4 switch 嵌套子节点(branchName 含 ">" 即嵌套链路 tag)
          const nested = run.steps.filter((s) =>
            s.parentIndex != null && s.parentIndex >= 0 && (s.branchName || '').includes('>')).length;
          const plain = run.steps.filter((s) =>
            s.parentIndex != null && s.parentIndex >= 0 && !(s.branchName || '').includes('>')).length;
          return (
            <>
              {plain > 0 && <Tag color="blue">parallel ×{plain}</Tag>}
              {nested > 0 && <Tag color="purple">嵌套 ×{nested}</Tag>}
            </>
          );
        })()}
      </Space>
      <div style={{ fontSize: 12, color: 'var(--text-3, #999)', margin: '8px 0' }}>
        {fmtTime(run.startedAt)} → {run.finishedAt ? fmtTime(run.finishedAt) : '进行中'}
      </div>

      {/* 时间轴(spec C2 增强):横条按 durationMs 等比宽,状态色 */}
      {run.steps.length > 0 && (
        <div
          style={{
            margin: '4px 0 12px 0',
            padding: '8px 12px',
            background: 'var(--bg-sunken, #0a0a0a05)',
            borderRadius: 6,
          }}
        >
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 6 }}>时间轴</Text>
          <div style={{ position: 'relative', height: 24, background: 'var(--ant-color-fill-tertiary, #f5f5f5)', borderRadius: 4 }}>
            {(() => {
              const sumDur = run.steps.reduce((a, s) => a + (s.durationMs || 0), 0) || 1;
              let offset = 0;
              return run.steps.map((s, idx) => {
                const dur = s.durationMs || 0;
                const w = (dur / sumDur) * 100;
                const left = offset;
                offset += w;
                return (
                  <Tooltip
                    key={s.name + idx}
                    title={`${s.name} (${s.status}) · ${fmtDuration(s.durationMs)} · click 详情`}
                  >
                    <div
                      role="button"
                      tabIndex={0}
                      onClick={() => setSelectedStep(s)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') setSelectedStep(s); }}
                      style={{
                        position: 'absolute',
                        left: `${left}%`,
                        width: `${Math.max(w, 0.5)}%`,
                        top: 2,
                        bottom: 2,
                        background: s.status === 'FAILED'
                          ? '#ff4d4f'
                          : s.status === 'COMPLETED' ? '#52c41a' : '#1677ff',
                        borderRadius: 3,
                        opacity: 0.85,
                        cursor: 'pointer',
                      }}
                    />
                  </Tooltip>
                );
              });
            })()}
          </div>
        </div>
      )}

      {/* 时间轴点击 → step 详情 Drawer(spec C2 增强交互) */}
      <Drawer
        title={selectedStep ? `Step: ${selectedStep.name}` : ''}
        open={!!selectedStep}
        onClose={() => setSelectedStep(null)}
        width={520}
      >
        {selectedStep && (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="状态">
              <Tag color={STEP_STATUS_COLOR[selectedStep.status] || 'default'}>
                {selectedStep.status}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="耗时">{fmtDuration(selectedStep.durationMs)}</Descriptions.Item>
            <Descriptions.Item label="branch">
              {selectedStep.branchName || '—'}
            </Descriptions.Item>
            {selectedStep.errorMessage && (
              <Descriptions.Item label="错误">
                <span style={{ color: '#ff4d4f' }}>{selectedStep.errorMessage}</span>
              </Descriptions.Item>
            )}
            <Descriptions.Item label="Inputs">
              <pre style={{ fontSize: 11, maxHeight: 160, overflow: 'auto', background: 'var(--bg-sunken, #0a0a0a05)', padding: 8, borderRadius: 4 }}>
                {JSON.stringify(selectedStep.inputs, null, 2)}
              </pre>
            </Descriptions.Item>
            <Descriptions.Item label="Outputs">
              <pre style={{ fontSize: 11, maxHeight: 200, overflow: 'auto', background: 'var(--bg-sunken, #0a0a0a05)', padding: 8, borderRadius: 4 }}>
                {JSON.stringify(selectedStep.outputs, null, 2)}
              </pre>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      {/* 顶层步骤(单步) */}
      {topLevel.length > 0 && (
        <Table<StepRun>
          rowKey="name" size="small" dataSource={topLevel} pagination={false}
          columns={stepColumns}
        />
      )}

      {/* 嵌套节点可视化(C2 parallel + C4 switch 嵌套,按 parentIndex 分组)。
          branchName 是链路 tag(如 "sw>case:value=a>b1"):≥2 段 → switch 嵌套,按 tag 前缀聚合面包屑 */}
      {[...groups.entries()].sort(([a], [b]) => a - b).map(([parentIdx, branches]) => {
        const failed = branches.filter((b) => b.status === 'FAILED').length;
        const succeeded = branches.length - failed;
        // tag 形如 "sw>case:value=a"(switch 子 Single)或 "sw>case:value=a>b1"(switch>parallel 分支);
        // 顶层 parallel 分支 tag 是普通 branch 名(无 ">")— 与 C2 行为一致
        const tags = branches
          .map((b) => (b.branchName || '').split('>'))
          .filter((segs) => segs.length >= 2);
        const breadcrumb = tags.length > 0 ? tags[0].slice(0, -1).join(' › ') : null;
        const isSwitchNested = tags.length > 0;
        return (
          <div
            key={parentIdx}
            style={{
              margin: '8px 0',
              padding: '8px 12px',
              border: '1px solid var(--ant-color-border, #d9d9d9)',
              borderLeft: isSwitchNested
                ? '3px solid ' + (failed > 0 ? '#ff4d4f' : '#722ed1')
                : undefined,
              borderRadius: 6,
              background: 'var(--bg-sunken, #0a0a0a05)',
            }}
          >
            <Space size={6} style={{ marginBottom: 6 }} wrap>
              <Tag color={isSwitchNested ? 'purple' : 'blue'}>
                {isSwitchNested ? 'switch 嵌套' : 'parallel'}
              </Tag>
              {breadcrumb ? (
                <Text type="secondary" style={{ fontSize: 11, fontFamily: 'monospace' }}>
                  {breadcrumb}
                </Text>
              ) : (
                <Text strong style={{ fontSize: 12 }}>branch #{parentIdx}</Text>
              )}
              <Text type="secondary" style={{ fontSize: 11 }}>{succeeded} ok / {failed} fail</Text>
            </Space>
            <Table<StepRun>
              rowKey={(r) => `b${parentIdx}-${r.name}`}
              size="small" dataSource={branches} pagination={false}
              columns={stepColumns}
            />
          </div>
        );
      })}
    </div>
  );
}

const stepColumns = [
  { title: 'Step', dataIndex: 'name', render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
  { title: '状态', dataIndex: 'status', width: 80,
    render: (s: string) => <Tag color={STEP_STATUS_COLOR[s] || 'default'} style={{ fontSize: 11 }}>{s}</Tag> },
  { title: '耗时', dataIndex: 'durationMs', width: 80, render: fmtDuration },
  { title: 'branch', dataIndex: 'branchName', width: 160,
    render: (b: string | null | undefined) => b
      ? (b.includes('>')
          ? <Tooltip title={`嵌套链路: ${b}`}><code style={{ fontSize: 10 }}>{b}</code></Tooltip>
          : <Tag color="geekblue">{b}</Tag>)
      : '—' },
  { title: '输出', dataIndex: 'outputs',
    render: (v: Record<string, unknown>) => (
      <code style={{ fontSize: 11 }}>
        {Object.keys(v).length === 0 ? '—' : JSON.stringify(v).slice(0, 80)}
      </code>
    ) },
  { title: '错误', dataIndex: 'errorMessage',
    render: (e: string | null) => e ? <Text type="danger" style={{ fontSize: 11 }}>{e}</Text> : '—' },
];

// ==================== 定义库 Tab ====================

function DefinitionsTab() {
  const [defs, setDefs] = useState<WorkflowDefinition[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<WorkflowDefinition | null>(null);
  const [creating, setCreating] = useState(false);
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formBody, setFormBody] = useState(SAMPLE_DEF);

  const load = async () => {
    setLoading(true);
    try { setDefs(await listDefinitions()); }
    catch (e) { message.error('加载定义失败: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  useEffect(() => { void load(); }, []);

  const onEdit = async (name: string) => {
    try {
      const d = await getDefinition(name);
      setEditing(d);
      setFormName(d.name);
      setFormDesc(d.description || '');
      setFormBody(d.body);
      setCreating(false);
    } catch (e) { message.error('加载定义失败: ' + (e as Error).message); }
  };

  const onSave = async () => {
    if (!formName.trim()) { message.error('name 必填'); return; }
    try {
      JSON.parse(formBody);  // 客户端预校验
    } catch (e) { message.error('body 不是合法 JSON: ' + (e as Error).message); return; }
    try {
      if (editing) {
        await updateDefinition(editing.name, { description: formDesc, body: formBody });
        message.success('已更新');
      } else {
        await saveDefinitionJson({ name: formName, description: formDesc, body: formBody });
        message.success('已创建');
      }
      setEditing(null);
      setCreating(false);
      void load();
    } catch (e) { message.error('保存失败: ' + (e as Error).message); }
  };

  const onDelete = async (name: string) => {
    try {
      await deleteDefinition(name);
      message.success(`已删除 ${name}`);
      if (editing?.name === name) setEditing(null);
      void load();
    } catch (e) { message.error('删除失败: ' + (e as Error).message); }
  };

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} lg={editing || creating ? 12 : 24}>
        <Card
          title="定义列表"
          size="small"
          extra={
            <Space>
              <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => { setCreating(true); setEditing(null); setFormName(''); setFormDesc(''); setFormBody(SAMPLE_DEF); }}>
                新建
              </Button>
            </Space>
          }
        >
          <Table<WorkflowDefinition>
            rowKey="name" size="small" dataSource={defs} loading={loading}
            pagination={{ pageSize: 15, showSizeChanger: false }}
            locale={{ emptyText: <EmptyState variant="no-data" description="还没有定义" /> }}
            onRow={(d) => ({ onClick: () => onEdit(d.name), style: { cursor: 'pointer' } })}
            columns={[
              { title: 'name', dataIndex: 'name', render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
              { title: '描述', dataIndex: 'description',
                render: (v: string | null) => v || <Text type="secondary">—</Text> },
              { title: 'format', dataIndex: 'format', width: 70,
                render: (v: string) => <Tag>{v}</Tag> },
              { title: '更新', dataIndex: 'updatedAt', width: 130,
                render: (v: string) => <span style={{ fontSize: 11 }}>{fmtTime(v)}</span> },
              { title: '操作', key: 'op', width: 80,
                render: (_, d) => (
                  <Button danger size="small" type="text" icon={<DeleteOutlined />}
                    onClick={(e) => { e.stopPropagation(); onDelete(d.name); }}>
                    删除
                  </Button>
                ) },
            ]}
          />
        </Card>
      </Col>

      {(editing || creating) && (
        <Col xs={24} lg={12}>
          <Card
            title={editing ? `编辑: ${editing.name}` : '新建定义'}
            size="small"
            extra={
              <Space>
                <Button onClick={() => { setEditing(null); setCreating(false); }}>取消</Button>
                <Button type="primary" onClick={onSave}>保存</Button>
              </Space>
            }
          >
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Input
                addonBefore="name" value={formName}
                onChange={(e) => setFormName(e.target.value)}
                disabled={!!editing}  // name 不可改(update 用 path)
              />
              <Input
                addonBefore="描述" value={formDesc}
                onChange={(e) => setFormDesc(e.target.value)}
                placeholder="可选"
              />
              <div>
                <Text type="secondary" style={{ fontSize: 11 }}>body (WorkflowDef JSON):</Text>
                <Input.TextArea
                  rows={18} value={formBody} onChange={(e) => setFormBody(e.target.value)}
                  style={{ fontFamily: 'monospace', fontSize: 11, marginTop: 4 }} spellCheck={false}
                />
              </div>
            </Space>
          </Card>
        </Col>
      )}
    </Row>
  );
}