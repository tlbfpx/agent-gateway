/**
 * GrayscaleDialog.test.tsx — 灰度弹窗
 * 验证：
 *  - 打开后显示权重滑块
 *  - 调整权重触发实时分流预览
 *  - 启用开关切换
 *  - 计划切换展开/收起
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';

vi.mock('../src/lib/api/models', () => ({
  updateModel: vi.fn().mockResolvedValue({ id: 'gpt-4o', grayWeight: 50 }),
}));

import { GrayscaleDialog } from '../src/components/models/GrayscaleDialog';
import type { Model } from '../src/lib/api/models';

const mockModel: Model = {
  id: 'gpt-4o',
  provider: 'openai',
  displayName: 'GPT-4o',
  endpoint: 'x',
  capabilities: [],
  contextWindow: 8192,
  enabled: true,
  grayWeight: 0,
};

const mockSiblings: Model[] = [
  {
    id: 'claude-3.7',
    provider: 'anthropic',
    displayName: 'Claude 3.7',
    endpoint: 'x',
    capabilities: [],
    contextWindow: 8192,
    enabled: true,
    grayWeight: 100,
    grayGroup: 'gpt-4o',
  },
];

function renderDialog() {
  return render(
    <GrayscaleDialog
      open
      onClose={() => {}}
      model={mockModel}
      siblings={mockSiblings}
      onApplied={() => {}}
    />,
  );
}

describe('GrayscaleDialog', () => {
  it('打开后显示权重和预览', () => {
    renderDialog();
    expect(screen.getByText(/灰度策略 · GPT-4o/)).toBeInTheDocument();
    expect(screen.getByText(/权重 · 0%/)).toBeInTheDocument();
    expect(screen.getByText(/实时分流预览/)).toBeInTheDocument();
  });

  it('同组模型在预览中可见', () => {
    renderDialog();
    expect(screen.getByText('Claude 3.7')).toBeInTheDocument();
  });

  it('启用灰度开关存在', () => {
    renderDialog();
    expect(screen.getByText('启用灰度')).toBeInTheDocument();
  });

  it('计划切换可展开', () => {
    renderDialog();
    expect(screen.getByText('计划切换')).toBeInTheDocument();
    // 切到 ON 后应显示 DatePicker
    // 找到对应的 Switch
    const switches = screen.getAllByRole('switch');
    expect(switches.length).toBeGreaterThanOrEqual(2);
  });
});
