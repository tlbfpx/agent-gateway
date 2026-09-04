/**
 * CommandPalette.test.tsx — 全局 ⌘K 命令面板
 * 注意：Sidebar 也有同名菜单项，必须用 Modal 范围定位
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

vi.mock('../src/lib/api/models', () => ({
  listModels: vi.fn().mockResolvedValue([
    { id: 'gpt-4o', provider: 'openai', displayName: 'GPT-4o', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: true },
    { id: 'claude-3.7', provider: 'anthropic', displayName: 'Claude 3.7', endpoint: 'x', capabilities: [], contextWindow: 8192, enabled: true },
  ]),
}));
vi.mock('../src/lib/api/keys', () => ({
  listApiKeys: vi.fn().mockResolvedValue([
    { id: 'pk_live_abc1234', tenant: 'primary', enabled: true, createdAt: '2026-01-01' },
  ]),
}));
vi.mock('../src/lib/api/agents', () => ({
  listAgents: vi.fn().mockResolvedValue([
    { name: 'weather-mcp', description: '天气查询', skills: ['weather'], available: true },
  ]),
}));

import { AppShell } from '../src/layouts/AppShell';
import { fuzzyScore, rankBy } from '../src/lib/fuzzy';
import { closePalette } from '../src/hooks/useCommandPalette';

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <AppShell />
    </MemoryRouter>,
  );
}

/** 取出 antd Modal 的内容容器（.ant-modal） */
function getModal() {
  return document.querySelector('.ant-modal') as HTMLElement;
}

describe('CommandPalette — 开关', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    closePalette();
  });

  it('⌘K 打开命令面板', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    await waitFor(() => {
      expect(getModal()).toBeInTheDocument();
      expect(screen.getByPlaceholderText(/搜索菜单/)).toBeInTheDocument();
    });
  });

  it('Ctrl+K 同样工作', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    await waitFor(() => {
      expect(getModal()).toBeInTheDocument();
    });
  });

  it('Esc 关闭面板', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    const input = await screen.findByPlaceholderText(/搜索菜单/);
    fireEvent.keyDown(input, { key: 'Escape' });
    // destroyOnClose → modal 从 DOM 移除
    await waitFor(() => {
      expect(getModal()).not.toBeInTheDocument();
    });
  });
});

describe('CommandPalette — 搜索与渲染', () => {
  beforeEach(() => {
    // 每轮确保面板关闭（避免前一轮残留状态）
    closePalette();
  });

  it('打开后渲染导航分组', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    await waitFor(() => expect(getModal()).toBeInTheDocument());
    // 在 modal 范围内查找唯一的 group 标签
    const modal = getModal();
    expect(within(modal).getByText(/^导航 \·/)).toBeInTheDocument();
    expect(within(modal).getByText('模型管理')).toBeInTheDocument();
    expect(within(modal).getByText('API Key')).toBeInTheDocument();
  });

  it('输入关键字后命中模型', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    const input = await screen.findByPlaceholderText(/搜索菜单/);
    fireEvent.change(input, { target: { value: 'gpt' } });
    await waitFor(() => {
      const modal = getModal();
      expect(within(modal).getByText('GPT-4o')).toBeInTheDocument();
    });
  });

  it('空查询显示导航 + 元数据', async () => {
    renderShell();
    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    await waitFor(() => {
      const modal = getModal();
      expect(within(modal).getByText('weather-mcp')).toBeInTheDocument();
      expect(within(modal).getByText('pk_live_abc1234')).toBeInTheDocument();
    });
  });
});

describe('fuzzyScore', () => {
  it('完全相等 → 1000', () => expect(fuzzyScore('gpt', 'gpt')).toBe(1000));
  it('起始前缀 → 100', () => expect(fuzzyScore('gpt', 'gpt-4o')).toBe(100));
  it('子串包含 → 5', () => expect(fuzzyScore('4o', 'gpt-4o')).toBe(5));
  it('顺序匹配 → 有分', () => expect(fuzzyScore('g4', 'gpt-4o')).toBeGreaterThan(0));
  it('不匹配 → 0', () => expect(fuzzyScore('xyz', 'gpt-4o')).toBe(0));
  it('空查询 → 1', () => expect(fuzzyScore('', 'gpt-4o')).toBe(1));
  it('rankBy 排序', () => {
    const r = rankBy(['gpt-4o', 'claude-3.7', 'gpt-3.5'], 'gpt', (s) => s);
    expect(r[0]).toBe('gpt-4o');
    expect(r[1]).toBe('gpt-3.5');
  });
});
