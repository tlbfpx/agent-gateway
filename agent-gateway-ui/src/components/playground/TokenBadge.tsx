/**
 * TokenBadge — 流结束后展示 token / 延迟 / finish_reason
 *
 * 数据来源：runPlayground 的 onDone(meta)，仅在 !streaming 时渲染。
 * 字段缺省时显示「—」，保证角标始终存在（视觉锚点）。
 */
import { Tag, Space } from 'antd';

interface Meta {
  model?: string;
  tokensIn?: number;
  tokensOut?: number;
  latencyMs?: number;
  finishReason?: string;
  cacheHit?: boolean;
}

interface Props {
  meta?: Meta;
  /** 自定义前缀，例如 Compare 模式的左右 "A" / "B" */
  prefix?: string;
}

export function TokenBadge({ meta, prefix }: Props) {
  if (!meta) return null;
  const fmt = (n?: number) => (typeof n === 'number' ? n.toLocaleString() : '—');
  return (
    <Space size={6} style={{ fontSize: 11, opacity: 0.85 }} className="mono">
      {prefix && <Tag color="blue">{prefix}</Tag>}
      {meta.model && <span>{meta.model}</span>}
      <span>↑{fmt(meta.tokensIn)} ↓{fmt(meta.tokensOut)} tok</span>
      <span>· {typeof meta.latencyMs === 'number' ? `${meta.latencyMs} ms` : '—'}</span>
      <span>· {meta.finishReason ?? 'stop'}</span>
      {meta.cacheHit === true && (
        <Tag color="green" style={{ fontSize: 10, lineHeight: '14px', paddingInline: 4 }}>
          缓存命中
        </Tag>
      )}
    </Space>
  );
}