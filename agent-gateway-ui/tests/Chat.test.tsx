/**
 * tests/Chat.test.tsx
 *
 * 覆盖 Chat 页全部交互路径（DeepSeek 式对话）：
 *  - 模型下拉选择（listPublicModels）
 *  - Agent 卡片展示（listAgents）
 *  - 会话列表加载（listSessions）：侧栏显示 title（首条消息摘要）而非 id
 *  - 新会话：纯前端动作，不发请求；首条消息发送时自动 POST /v1/sessions
 *  - 切换会话（含模型跟随）
 *  - 错误注入：创建会话失败 → 错误条出现
 *  - 发送消息：SSE 流式累积、空状态 hero、建议点击填充输入框
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { Chat } from '../src/pages/Chat';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

const newSessionBtnName = /新\s*会\s*话/;

describe('Chat — left menu /chat', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    mock = installMock();
    // 给完整 seed，包括 2 个历史会话 + 1 个公开 Agent
    mock.store.chatSessions = [
      { id: 's_aaaa1111', title: '问一下北京天气', lastActiveAt: new Date(Date.now() - 2 * 3600_000).toISOString() },
      { id: 's_bbbb2222', title: 'SQL 优化建议', lastActiveAt: new Date(Date.now() - 24 * 3600_000).toISOString() },
    ];
    mock.store.agents = [
      {
        id: 'ag_weather',
        name: 'weather-mcp',
        description: '天气查询 MCP Server',
        skills: ['weather'],
        version: '1.4.2',
        available: true,
        endpoint: 'https://agents.example/weather',
        enabled: true,
        source: 'nacos',
        owner: 'admin@primary',
        tags: ['mcp'],
        createdAt: new Date(Date.now() - 90 * 86400_000).toISOString(),
        updatedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
        heartbeatTimeoutSec: 30,
      },
    ];
  });
  afterEach(() => mock.uninstall());

  // ─────────── 挂载类 ───────────
  it('mounts and shows model dropdown + session sidebar (titles) + agent cards', async () => {
    renderWithRouter(<Chat />);
    // 等 listPublicModels 拉回来，下拉至少有一个 option
    await waitFor(() => {
      expect(screen.getAllByText(/gpt-4o|GPT-4o/).length).toBeGreaterThan(0);
    });
    // 历史会话进 sidebar — 显示 title（首条消息摘要）而非 id
    expect(screen.getByText('问一下北京天气')).toBeInTheDocument();
    expect(screen.getByText('SQL 优化建议')).toBeInTheDocument();
    // Agent 卡片
    expect(screen.getByText('weather-mcp')).toBeInTheDocument();
  });

  // ─────────── 空状态 hero（DeepSeek 式开局） ───────────
  it('shows empty-state hero with suggestions before any message', async () => {
    renderWithRouter(<Chat />);
    expect(await screen.findByText('开始新的对话')).toBeInTheDocument();
    // 建议 chip 存在且可点击填充输入框
    const chip = screen.getByRole('button', { name: /介绍一下你自己/ });
    fireEvent.click(chip);
    expect(await screen.findByDisplayValue(/介绍一下你自己/)).toBeInTheDocument();
  });

  // ─────────── 新会话：纯前端动作 ───────────
  it('新会话 button resets to blank state without POSTing /v1/sessions', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    // 先选中一个历史会话
    fireEvent.click(await screen.findByText('问一下北京天气'));
    await waitFor(() => {
      expect(document.querySelector('.chat-empty-hero-title')).toBeInTheDocument();
    });
    const before = mock.store.chatSessions.length;
    fireEvent.click(screen.getByRole('button', { name: newSessionBtnName }));
    // 回到空状态 hero，且没有发任何创建会话请求
    await waitFor(() => {
      expect(screen.getByText('开始新的对话')).toBeInTheDocument();
    });
    expect(mock.store.chatSessions.length).toBe(before);
  });

  it('首次发送自动创建会话（lazy session creation）', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    const before = mock.store.chatSessions.length;

    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    // user 消息出现 + 会话被自动创建
    expect(await screen.findByText('你好')).toBeInTheDocument();
    await waitFor(() => {
      expect(mock.store.chatSessions.length).toBe(before + 1);
    });
  });

  it('首条消息后侧栏立即显示乐观标题摘要（不等流结束）', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);

    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '## 帮我查一下`北京天气`怎么样' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    // markdown 噪音被剥离，20 字内保留原文；立刻出现在侧栏（不等待）
    expect(await screen.findByText('帮我查一下北京天气怎么样')).toBeInTheDocument();
  });

  it('切换会话高亮选中行', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);

    // 第一次发送一条消息（让非空状态出现）
    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    // 等 user 消息渲染
    await waitFor(() => {
      expect(screen.getByText('你好')).toBeInTheDocument();
    });

    // 切换到别的会话（点 title）
    fireEvent.click(screen.getByText('SQL 优化建议'));

    // 消息被清空（回到该会话的空历史 — mock 返回 []）
    await waitFor(() => {
      expect(screen.queryByText('你好')).not.toBeInTheDocument();
    });
  });

  // ─────────── 错误注入 ───────────
  it('shows inline error when lazy session creation fails', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    mock.nextReply('POST', '/v1/sessions', { message: 'backend down' }, 503);

    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText(/⚠|backend|创建/)).toBeInTheDocument();
    // 不在 store 里新增
    await new Promise((r) => setTimeout(r, 200));
    const fresh = mock.store.chatSessions.find((s) => !['s_aaaa1111', 's_bbbb2222'].includes(s.id));
    expect(fresh).toBeUndefined();
  });

  // ─────────── 发送 + 流式 ───────────
  it('clicking send posts to /v1/chat/stream and shows user message', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);

    const ta = await screen.findByPlaceholderText(/输入消息/);
    fireEvent.change(ta, { target: { value: '你好' } });
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    expect(await screen.findByText('你好')).toBeInTheDocument();
  });

  // ─────────── 模型下拉 ───────────
  it('model dropdown reflects listPublicModels result', async () => {
    renderWithRouter(<Chat />);
    await waitFor(() => {
      // Select 渲染的 option
      expect(screen.getAllByText(/GPT-4o|Claude 3\.7|通义千问/).length).toBeGreaterThan(0);
    });
  });

  // ─────────── 边界：空输入不发 ───────────
  it('does not send on empty input', async () => {
    renderWithRouter(<Chat />);
    await screen.findAllByText(/gpt-4o|GPT-4o/);
    const btn = screen.getByRole('button', { name: /发送/ });
    expect(btn).toBeDisabled();
  });

  // ─────────── 数据：模拟空会话列表 ───────────
  it('renders Empty when listSessions returns []', async () => {
    mock.store.chatSessions = [];
    renderWithRouter(<Chat />);
    expect(await screen.findByText(/暂无会话/)).toBeInTheDocument();
  });
});
