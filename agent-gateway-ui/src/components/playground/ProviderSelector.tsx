/**
 * ProviderSelector — provider + model 二级级联下拉
 *
 * 数据源：listPublicModels → ModelInfo[]
 * - 第一级 Select：去重后的 provider
 * - 第二级 Select：当前 provider 下的 model
 */
import { useMemo } from 'react';
import { Select, Space } from 'antd';
import type { ModelInfo } from '../../lib/api/agents';

interface Props {
  models: ModelInfo[];
  provider: string;
  model: string;
  onProviderChange: (p: string) => void;
  onModelChange: (m: string) => void;
  disabled?: boolean;
  idPrefix?: string;
}

export function ProviderSelector({
  models,
  provider,
  model,
  onProviderChange,
  onModelChange,
  disabled,
  idPrefix = 'pg',
}: Props) {
  const providers = useMemo(() => {
    const set = new Set<string>();
    for (const m of models) {
      if (m.provider) set.add(m.provider);
    }
    return Array.from(set).sort();
  }, [models]);

  const modelOptions = useMemo(() => {
    return models
      .filter((m) => !provider || m.provider === provider)
      .map((m) => ({
        value: m.modelId,
        label: m.displayName || m.modelId,
      }));
  }, [models, provider]);

  return (
    <Space size={8}>
      <Select
        value={provider || undefined}
        onChange={onProviderChange}
        disabled={disabled}
        placeholder="Provider"
        style={{ width: 140 }}
        options={providers.map((p) => ({ value: p, label: p }))}
        data-testid={`${idPrefix}-provider`}
        aria-label="选择 Provider"
      />
      <Select
        value={model || undefined}
        onChange={onModelChange}
        disabled={disabled || !provider}
        placeholder="Model"
        style={{ width: 220 }}
        options={modelOptions}
        showSearch
        optionFilterProp="label"
        data-testid={`${idPrefix}-model`}
        aria-label="选择模型"
      />
    </Space>
  );
}