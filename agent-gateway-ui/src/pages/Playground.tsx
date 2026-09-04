/**
 * pages/Playground.tsx
 *
 * Prompt Playground — 单模型 / 双 pane 对比。
 *
 * - 单模式：1 个 ProviderSelector + 4 ParamSlider + 1 个输出区 + 1 个运行/停止按钮
 * - 对比模式：左右两个独立 ComparePane，共享 system/user
 *
 * 后端复用：`POST /v1/chat/stream`（带 system/temperature/topP/maxTokens 扩展字段）
 *
 * 模板：localStorage 'agent-gateway.playground.templates' — 暂存本 Round，
 *      下 Round 接后端持久化。
 */
import { useEffect, useRef, useState } from 'react';
import { Button, Empty, Input, Modal, Segmented, Select, Space, message } from 'antd';
import {
  PlayCircleOutlined,
  StopOutlined,
  SaveOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { ProviderSelector } from '../components/playground/ProviderSelector';
import { ParamSlider } from '../components/playground/ParamSlider';
import { TokenBadge } from '../components/playground/TokenBadge';
import { ComparePane } from '../components/playground/ComparePane';
import {
  runPlayground,
  listTemplates,
  saveTemplate,
  deleteTemplate,
  type PlaygroundMeta,
  type PlaygroundStreamCall,
} from '../lib/api/playground';
import { listPublicModels } from '../lib/api/agents';
import type { ModelInfo } from '../lib/api/agents';
import { ErrorState } from '../components/framework/EmptyState';
import { CodeSnippet } from '../components/CodeSnippet';
import type { CodegenRequest } from '../lib/codegen';

type Mode = 'single' | 'compare';

export function Playground() {
  /* ───── 模型数据 ───── */
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [booted, setBooted] = useState(false);

  /* ───── 模式 + 表单（共享） ───── */
  const [mode, setMode] = useState<Mode>('single');
  const [system, setSystem] = useState('');
  const [user, setUser] = useState('');

  /* ───── 单模式 state ───── */
  const [provider, setProvider] = useState('');
  const [model, setModel] = useState('');
  const [temperature, setTemperature] = useState(0.7);
  const [topP, setTopP] = useState(1);
  const [maxTokens, setMaxTokens] = useState(2048);

  /* ───── 对比模式 state（左 A / 右 B） ───── */
  const [provA, setProvA] = useState('');
  const [modelA, setModelA] = useState('');
  const [tempA, setTempA] = useState(0.7);
  const [topPA, setTopPA] = useState(1);
  const [maxA, setMaxA] = useState(2048);
  const [provB, setProvB] = useState('');
  const [modelB, setModelB] = useState('');
  const [tempB, setTempB] = useState(0.7);
  const [topPB, setTopPB] = useState(1);
  const [maxB, setMaxB] = useState(2048);

  /* ───── 单模式 流输出 ───── */
  const [output, setOutput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [meta, setMeta] = useState<PlaygroundMeta | undefined>();
  const [error, setError] = useState('');
  const callRef = useRef<PlaygroundStreamCall | null>(null);

  /* ───── 模板 ───── */
  const [templates, setTemplates] = useState(listTemplates());
  const [selectedTpl, setSelectedTpl] = useState<string>('');
  const [saveModalOpen, setSaveModalOpen] = useState(false);
  const [tplName, setTplName] = useState('');

  /* ───── 初始化：拉模型 ───── */
  useEffect(() => {
    (async () => {
      try {
        const ms = await listPublicModels();
        setModels(ms);
        if (ms.length > 0) {
          const first = ms[0];
          setProvider(first.provider ?? '');
          setModel(first.modelId);
          setProvA(first.provider ?? '');
          setModelA(first.modelId);
          // 第二 pane 默认挑不同 provider (若存在)
          const alt = ms.find((m) => m.provider && m.provider !== first.provider);
          if (alt) {
            setProvB(alt.provider ?? '');
            setModelB(alt.modelId);
          } else {
            setProvB(first.provider ?? '');
            setModelB(first.modelId);
          }
        }
      } catch {
        /* 离线态：保持空，UI 走 empty */
      } finally {
        setBooted(true);
      }
    })();
  }, []);

  /* ───── 单模式 run / stop ───── */
  const canRunSingle = !!model && !!user.trim() && !streaming;

  const run = () => {
    setOutput('');
    setMeta(undefined);
    setError('');
    setStreaming(true);
    const call = runPlayground(
      {
        model,
        system,
        prompt: user,
        temperature,
        topP,
        maxTokens,
      },
      {
        onChunk: (_d, acc) => setOutput(acc),
        onDone: (full, m) => {
          setOutput(full);
          setMeta(m);
          setStreaming(false);
        },
        onError: (msg) => {
          setError(msg);
          setStreaming(false);
        },
      },
    );
    callRef.current = call;
    call.promise.finally(() => { callRef.current = null; });
  };

  const stop = () => {
    callRef.current?.stop();
    setStreaming(false);
  };

  /* ───── 模板保存 / 载入 / 删除 ───── */
  const onSaveTemplate = () => {
    if (!user.trim()) {
      message.warning('请先填写 User Prompt');
      return;
    }
    setTplName('');
    setSaveModalOpen(true);
  };

  const confirmSave = () => {
    const name = tplName.trim() || `模板 ${new Date().toLocaleTimeString()}`;
    saveTemplate({
      name,
      system,
      user,
      temperature,
      topP,
      maxTokens,
      model,
    });
    setTemplates(listTemplates());
    setSaveModalOpen(false);
    message.success(`已保存模板：${name}`);
  };

  const onLoadTemplate = (id: string) => {
    const t = templates.find((x) => x.id === id);
    if (!t) return;
    setSystem(t.system);
    setUser(t.user);
    setTemperature(t.temperature);
    setTopP(t.topP);
    setMaxTokens(t.maxTokens);
    // 模型
    const m = models.find((x) => x.modelId === t.model);
    if (m) {
      setProvider(m.provider ?? '');
      setModel(m.modelId);
    } else {
      setModel(t.model);
    }
    setSelectedTpl(id);
    message.success(`已载入模板：${t.name}`);
  };

  const onDeleteTemplate = (id: string) => {
    deleteTemplate(id);
    setTemplates(listTemplates());
    if (selectedTpl === id) setSelectedTpl('');
    message.success('已删除模板');
  };

  return (
    <>
      <PageHeader
        eyebrow="Playground · 调试工作台"
        title="Prompt Playground"
        sub="选模型 · 调参数 · 看流式 · 并排对比"
        actions={
          <Space>
            <Segmented
              value={mode}
              onChange={(v) => setMode(v as Mode)}
              options={[
                { label: '单模式', value: 'single' },
                { label: '对比模式', value: 'compare' },
              ]}
              data-testid="pg-mode"
            />
            <Select
              value={selectedTpl || undefined}
              onChange={onLoadTemplate}
              placeholder="载入模板"
              style={{ width: 200 }}
              allowClear
              onClear={() => setSelectedTpl('')}
              data-testid="pg-tpl-select"
              options={templates.map((t) => ({ value: t.id, label: t.name }))}
            />
            <Button icon={<SaveOutlined />} onClick={onSaveTemplate} data-testid="pg-tpl-save">
              保存为模板
            </Button>
            {selectedTpl && (
              <Button
                danger
                icon={<DeleteOutlined />}
                onClick={() => onDeleteTemplate(selectedTpl)}
                data-testid="pg-tpl-delete"
              >
                删除模板
              </Button>
            )}
          </Space>
        }
      />

      {/* 共享输入区：system + user */}
      <div className="content-card" style={{ marginBottom: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">Prompt 输入</div>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div>
            <div style={{ fontSize: 12, color: 'var(--text-3)', marginBottom: 4 }}>
              System Prompt
            </div>
            <Input.TextArea
              value={system}
              onChange={(e) => setSystem(e.target.value)}
              placeholder="（可选）系统提示词"
              autoSize={{ minRows: 2, maxRows: 6 }}
              data-testid="pg-system"
            />
          </div>
          <div>
            <div style={{ fontSize: 12, color: 'var(--text-3)', marginBottom: 4 }}>
              User Prompt
            </div>
            <Input.TextArea
              value={user}
              onChange={(e) => setUser(e.target.value)}
              placeholder="用户提示词"
              autoSize={{ minRows: 2, maxRows: 8 }}
              data-testid="pg-user"
            />
          </div>
        </div>
      </div>

      {mode === 'single' ? (
        <SingleMode
          models={models}
          booted={booted}
          provider={provider}
          model={model}
          onProviderChange={(p) => { setProvider(p); setModel(''); }}
          onModelChange={setModel}
          temperature={temperature} onTemperatureChange={setTemperature}
          topP={topP} onTopPChange={setTopP}
          maxTokens={maxTokens} onMaxTokensChange={setMaxTokens}
          output={output} streaming={streaming}
          meta={meta} error={error}
          canRun={canRunSingle}
          onRun={run} onStop={stop} onRetry={run}
          request={buildPlaygroundRequest({
            provider,
            model,
            system,
            user,
            temperature,
            topP,
            maxTokens,
          })}
        />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <ComparePane
            models={models}
            prefix="A"
            provider={provA} model={modelA}
            onProviderChange={(p) => { setProvA(p); setModelA(''); }}
            onModelChange={setModelA}
            temperature={tempA} onTemperatureChange={setTempA}
            topP={topPA} onTopPChange={setTopPA}
            maxTokens={maxA} onMaxTokensChange={setMaxA}
            system={system} user={user}
          />
          <ComparePane
            models={models}
            prefix="B"
            provider={provB} model={modelB}
            onProviderChange={(p) => { setProvB(p); setModelB(''); }}
            onModelChange={setModelB}
            temperature={tempB} onTemperatureChange={setTempB}
            topP={topPB} onTopPChange={setTopPB}
            maxTokens={maxB} onMaxTokensChange={setMaxB}
            system={system} user={user}
          />
        </div>
      )}

      <Modal
        title="保存为模板"
        open={saveModalOpen}
        onOk={confirmSave}
        onCancel={() => setSaveModalOpen(false)}
        okText="保存"
        cancelText="取消"
      >
        <div style={{ marginBottom: 8, fontSize: 12, color: 'var(--text-3)' }}>
          将当前 system / user / 参数 / 模型保存到本地（localStorage）。
        </div>
        <Input
          value={tplName}
          onChange={(e) => setTplName(e.target.value)}
          placeholder="模板名（留空将自动命名）"
          autoFocus
          data-testid="pg-tpl-name"
        />
      </Modal>
    </>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * 单模式子区：表单 + 输出 + 按钮
 * ──────────────────────────────────────────────────────────────────────────── */

interface SingleModeProps {
  models: ModelInfo[];
  booted: boolean;
  provider: string;
  model: string;
  onProviderChange: (p: string) => void;
  onModelChange: (m: string) => void;
  temperature: number;
  onTemperatureChange: (v: number) => void;
  topP: number;
  onTopPChange: (v: number) => void;
  maxTokens: number;
  onMaxTokensChange: (v: number) => void;
  output: string;
  streaming: boolean;
  meta?: PlaygroundMeta;
  error: string;
  canRun: boolean;
  onRun: () => void;
  onStop: () => void;
  onRetry: () => void;
  /** 当前请求的代码片段（已由父组件计算好） */
  request: CodegenRequest;
}

function SingleMode(props: SingleModeProps) {
  const {
    models, booted,
    provider, model, onProviderChange, onModelChange,
    temperature, onTemperatureChange,
    topP, onTopPChange,
    maxTokens, onMaxTokensChange,
    output, streaming, meta, error,
    canRun, onRun, onStop, onRetry,
    request,
  } = props;

  return (
    <div className="content-card">
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          justifyContent: 'space-between',
          marginBottom: 12,
        }}
      >
        <ProviderSelector
          models={models}
          provider={provider}
          model={model}
          onProviderChange={onProviderChange}
          onModelChange={onModelChange}
          disabled={streaming}
          idPrefix="pg-single"
        />
        {streaming ? (
          <Button danger icon={<StopOutlined />} onClick={onStop} data-testid="pg-stop">
            停止
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            disabled={!canRun}
            onClick={onRun}
            data-testid="pg-run"
          >
            运行
          </Button>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16, marginBottom: 12 }}>
        <ParamSlider
          label="Temperature"
          min={0} max={2} step={0.05}
          value={temperature} onChange={onTemperatureChange}
          disabled={streaming}
        />
        <ParamSlider
          label="Top-P"
          min={0} max={1} step={0.01}
          value={topP} onChange={onTopPChange}
          format={(v) => v.toFixed(2)}
          disabled={streaming}
        />
        <ParamSlider
          label="Max Tokens"
          min={256} max={32000} step={256}
          value={maxTokens} onChange={onMaxTokensChange}
          format={(v) => v.toLocaleString()}
          disabled={streaming}
        />
      </div>

      <div
        data-testid="pg-output"
        style={{
          minHeight: 240,
          maxHeight: 420,
          overflowY: 'auto',
          padding: 14,
          background: 'var(--bg-sunken)',
          border: '1px solid var(--border-thin)',
          borderRadius: 'var(--r-sm)',
          fontSize: 13,
          lineHeight: 1.6,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {error ? (
          <ErrorState error={error} onRetry={onRetry} />
        ) : output ? (
          <>
            {output}
            {streaming && <span className="chat-cursor" />}
          </>
        ) : streaming ? (
          <span className="chat-cursor" />
        ) : booted ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span style={{ color: 'var(--text-3)', fontSize: 12 }}>填写 Prompt 后点击「运行」</span>}
          />
        ) : (
          <span style={{ color: 'var(--text-3)', fontSize: 12 }}>加载模型中…</span>
        )}
      </div>

      {(meta || streaming) && (
        <div style={{ marginTop: 10 }}>
          <TokenBadge meta={meta} />
        </div>
      )}

      {/* 当前 Playground 请求的代码片段（cURL / Python / JS / Go） */}
      <div
        style={{ marginTop: 12 }}
        data-testid="pg-code-snippet"
      >
        <div
          style={{
            fontSize: 11,
            fontFamily: 'var(--font-mono)',
            letterSpacing: 1,
            color: 'var(--text-3)',
            textTransform: 'uppercase',
            marginBottom: 6,
          }}
        >
          当前请求（点击右上角复制）
        </div>
        <CodeSnippet request={request} defaultLang="curl" />
      </div>
    </div>
  );
}

/**
 * 把 Playground 的当前状态拼成 /v1/chat/stream 的代码片段。
 * 抽成模块顶层函数，方便单元测试和 Storybook。
 */
function buildPlaygroundRequest(p: {
  provider: string;
  model: string;
  system: string;
  user: string;
  temperature: number;
  topP: number;
  maxTokens: number;
}): CodegenRequest {
  const origin =
    typeof window !== 'undefined' && window.location?.origin
      ? window.location.origin
      : '';
  const messages: Array<{ role: string; content: string }> = [];
  if (p.system.trim()) messages.push({ role: 'system', content: p.system });
  messages.push({ role: 'user', content: p.user });
  return {
    method: 'POST',
    url: `${origin}/v1/chat/stream`,
    headers: {
      'Content-Type': 'application/json',
      'X-Provider': p.provider,
    },
    body: {
      model: p.model,
      messages,
      temperature: p.temperature,
      top_p: p.topP,
      max_tokens: p.maxTokens,
      stream: true,
    },
  };
}