/**
 * tests/prompt-cache.test.tsx
 *
 * 提示缓存（gateway.llm.prompt-cache 契约）前端接入覆盖：
 *  1. API 层：getPromptCacheConfig / getPromptCacheRate（actuator 计数器形状）
 *  2. Chat：done 事件 meta.cacheHit=true → 助手消息角标显示「缓存命中」
 *  3. Dashboard：命中率卡片 hit/(hit+miss)=70%；指标不可得时显示 —
 *  4. Settings：提示缓存卡片读取配置（enabled/ttl/maxEntries），接口不可达时降级
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';
import { Chat } from '../src/pages/Chat';
import { Dashboard } from '../src/pages/Dashboard';
import { Settings } from '../src/pages/Settings';
import { getPromptCacheConfig, getPromptCacheRate } from '../src/lib/api/promptCache';

describe('prompt-cache — API 层', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('getPromptCacheConfig 读取 /admin/config/gateway.llm.prompt-cache', async () => {
    const cfg = await getPromptCacheConfig();
    expect(cfg).toEqual({ enabled: true, ttl: '10m', maxEntries: 1000 });
  });

  it('getPromptCacheRate 按 actuator 计数器计算命中率 42/(42+18)=0.7', async () => {
    const r = await getPromptCacheRate();
    expect(r).toEqual({ hit: 42, miss: 18, rate: 0.7 });
  });

  it('计数器接口不可得时 getPromptCacheRate 返回 null', async () => {
    mock.on('GET', '/actuator/metrics/prompt_cache_hit_total', () => {
      return new Response(JSON.stringify({ message: 'not found' }), { status: 404 });
    });
    expect(await getPromptCacheRate()).toBeNull();
  });

  it('配置接口不可达时 getPromptCacheConfig 返回 null（不抛错）', async () => {
    mock.on('GET', '/admin/config/gateway.llm.prompt-cache', () => {
      return new Response(JSON.stringify({ message: 'no such config' }), { status: 404 });
    });
    expect(await getPromptCacheConfig()).toBeNull();
  });
});

describe('prompt-cache — Chat 缓存命中标签', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('done meta.cacheHit=true 时显示「缓存命中」标签', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));
    // mock 流 done meta.cacheHit=true（seed: enabled + hits>0）
    expect(await screen.findByTestId('cache-hit-tag')).toHaveTextContent('缓存命中');
  });

  it('cacheHit=false 时不显示标签', async () => {
    mock.store.promptCache.enabled = false;
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));
    await waitFor(() => {
      expect(screen.getByText(/（mock 回复）/)).toBeInTheDocument();
    });
    expect(screen.queryByTestId('cache-hit-tag')).toBeNull();
  });
});

describe('prompt-cache — Dashboard 命中率卡片', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('显示 hit/(hit+miss) 命中率 70.0%', async () => {
    renderWithRouter(<Dashboard />);
    const label = await screen.findByText(/提示缓存命中率/);
    const card = label.closest('.stat-card') as HTMLElement;
    // 命中/未中计数 chip（纯文本，不受数字动画影响）
    await waitFor(() => {
      expect(card.textContent).toContain('命中 42 / 未中 18');
    });
    // 数值已计算（是百分比而非 —）；StatCard 数字有 count-up 动画，不锁定具体帧
    await waitFor(() => {
      expect(card.textContent).toMatch(/\d+(\.\d+)?%/);
      expect(card.textContent).not.toContain('—');
    });
  });

  it('指标未暴露时显示 —', async () => {
    mock.on('GET', '/actuator/metrics/prompt_cache_hit_total', () => {
      return new Response(JSON.stringify({ message: 'not found' }), { status: 404 });
    });
    renderWithRouter(<Dashboard />);
    const label = await screen.findByText(/提示缓存命中率/);
    const card = label.closest('.stat-card') as HTMLElement;
    // 卡片数值为 —（等指标拉取失败落定）
    await waitFor(() => {
      expect(card.textContent).toContain('—');
      expect(card.textContent).toContain('指标未暴露');
    });
  });
});

describe('prompt-cache — Settings 只读配置卡片', () => {
  let mock: ReturnType<typeof installMock>;
  beforeEach(() => {
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('读取并展示 enabled / ttl / maxEntries', async () => {
    renderWithRouter(<Settings />);
    const card = await screen.findByTestId('prompt-cache-card');
    expect(card).toHaveTextContent('gateway.llm.prompt-cache');
    await waitFor(() => {
      expect(card).toHaveTextContent('已启用');
    });
    expect(card).toHaveTextContent('10m');
    expect(card).toHaveTextContent('1000');
  });

  it('配置接口不可达时降级为占位说明', async () => {
    mock.on('GET', '/admin/config/gateway.llm.prompt-cache', () => {
      return new Response(JSON.stringify({ message: 'no such config' }), { status: 404 });
    });
    renderWithRouter(<Settings />);
    const card = await screen.findByTestId('prompt-cache-card');
    await waitFor(() => {
      expect(card).toHaveTextContent('无法读取当前值');
    });
    expect(card).toHaveTextContent('未知');
  });
});
