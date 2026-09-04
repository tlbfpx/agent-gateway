/**
 * ParamSlider — 单参数滑块 (label + slider + 数值显示)
 *
 * 用法：<ParamSlider label="Temperature" min={0} max={2} step={0.05} value={0.7} onChange={...} format={v=>v.toFixed(2)} />
 */
import { Slider } from 'antd';

interface Props {
  label: string;
  min: number;
  max: number;
  step: number;
  value: number;
  onChange: (v: number) => void;
  /** 数值显示格式，默认 toFixed(2) */
  format?: (v: number) => string;
  disabled?: boolean;
  testId?: string;
}

export function ParamSlider({
  label,
  min,
  max,
  step,
  value,
  onChange,
  format = (v) => v.toFixed(2),
  disabled,
  testId,
}: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          fontSize: 12,
          color: 'var(--text-3)',
        }}
      >
        <span>{label}</span>
        <span className="mono" data-testid={testId}>{format(value)}</span>
      </div>
      <Slider
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(v) => onChange(v as number)}
        disabled={disabled}
        tooltip={{ formatter: (v) => format(v as number) }}
      />
    </div>
  );
}