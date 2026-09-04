/**
 * ComparePane — Compare 模式下的左/右独立 pane
 *
 * 每个 pane 持有：
 *   - 独立的 provider + model + 4 params
 *   - 独立 AbortController（点「停止」只停自己）
 *   - 独立输出区 + TokenBadge
 *
 * Props.onChange 把内部 state 冒泡到父组件，保证 playground 主页面拥有
 * 单一 source of truth，避免双 pane 状态互相干扰。
 */
import { useState, useRef } from 'react';
import { Button, Empty, Space } from 'antd';
import { PlayCircleOutlined, StopOutlined } from '@ant-design/icons';
import type { ModelInfo } from '../../lib/api/agents';
import { ProviderSelector } from './ProviderSelector';
import { ParamSlider } from './ParamSlider';
import { TokenBadge } from './TokenBadge';
import {
  runPlayground,
  type PlaygroundParams,
  type PlaygroundMeta,
  type PlaygroundStreamCall,
} from '../../lib/api/playground';

interface Props {
  models: ModelInfo[];
  prefix: 'A' | 'B';
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
  system: string;
  user: string;
}

export function ComparePane(props: Props) {
  const {
    models, prefix,
    provider, model, onProviderChange, onModelChange,
    temperature, onTemperatureChange,
    topP, onTopPChange,
    maxTokens, onMaxTokensChange,
    system, user,
  } = props;

  const [output, setOutput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [meta, setMeta] = useState<PlaygroundMeta | undefined>();
  const [error, setError] = useState<string>('');
  const callRef = useRef<PlaygroundStreamCall | null>(null);

  const canRun = !!model && !!user.trim() && !streaming;

  const run = () => {
    setOutput('');
    setMeta(undefined);
    setError('');
    setStreaming(true);
    const params: PlaygroundParams = {
      model,
      system,
      prompt: user,
      temperature,
      topP,
      maxTokens,
    };
    const call = runPlayground(params, {
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
    });
    callRef.current = call;
    call.promise.finally(() => { callRef.current = null; });
  };

  const stop = () => {
    callRef.current?.stop();
    setStreaming(false);
  };

  return (
    <div
      className="content-card"
      data-testid={`compare-pane-${prefix}`}
      style={{ display: 'flex', flexDirection: 'column', gap: 12 }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between' }}>
        <ProviderSelector
          models={models}
          provider={provider}
          model={model}
          onProviderChange={onProviderChange}
          onModelChange={onModelChange}
          disabled={streaming}
          idPrefix={`pg-${prefix.toLowerCase()}`}
        />
        {streaming ? (
          <Button danger icon={<StopOutlined />} onClick={stop} data-testid={`pg-${prefix.toLowerCase()}-stop`}>
            停止
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            onClick={run}
            disabled={!canRun}
            data-testid={`pg-${prefix.toLowerCase()}-run`}
          >
            运行
          </Button>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
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
        data-testid={`pg-${prefix.toLowerCase()}-output`}
        style={{
          minHeight: 220,
          maxHeight: 360,
          overflowY: 'auto',
          padding: 12,
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
          <div style={{ color: 'var(--ant-error)' }}>⚠ {error}</div>
        ) : output ? (
          <>
            {output}
            {streaming && <span className="chat-cursor" />}
          </>
        ) : streaming ? (
          <span className="chat-cursor" />
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<span style={{ color: 'var(--text-3)', fontSize: 12 }}>尚未运行</span>}
          />
        )}
      </div>

      {(meta || streaming) && (
        <Space style={{ flexWrap: 'wrap' }}>
          <TokenBadge meta={meta} prefix={prefix} />
        </Space>
      )}
    </div>
  );
}