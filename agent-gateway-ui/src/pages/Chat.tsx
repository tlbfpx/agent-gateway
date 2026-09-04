import { useEffect, useRef, useState, useCallback } from 'react';
import { Button, Input, Select, Space, Empty, Tag, Tooltip, message, Popconfirm } from 'antd';
import {
  SendOutlined,
  CopyOutlined,
  ReloadOutlined,
  DeleteOutlined,
  ShareAltOutlined,
  ArrowDownOutlined,
  StopOutlined,
  PlusOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import {
  streamChat,
  type StreamCall,
  createSession,
  listSessions,
  getMessages,
  deleteSession,
  summarizeTitle,
  type Session,
} from '../lib/api/chat';
import { listAgents, listPublicModels } from '../lib/api/agents';
import type { ModelInfo, AgentInfo } from '../lib/api/agents';
import { relTime } from '../lib/format';
import { MarkdownView } from '../components/chat/MarkdownView';
import { FeedbackButtons } from '../components/feedback/FeedbackButtons';

interface ToolCallTag {
  id: number;
  agent: string;
  phase: 'started' | 'done';
  success?: boolean;
}

interface Msg {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: ToolCallTag[];
  /** 用于重试的原始 prompt（assistant 消息记它对应的 user prompt） */
  prompt?: string;
  /** 是否正在生成中（含重试） */
  streaming?: boolean;
  /** 本轮用量元数据（done 事件携带）：实际命中模型 + token 估算 + 缓存命中标记 */
  meta?: { model: string; tokensIn: number; tokensOut: number; cacheHit?: boolean };
}

const SUGGESTIONS = [
  { icon: '🧭', text: '介绍一下你自己，你能调用哪些 Agent？' },
  { icon: '📊', text: '帮我总结最近的网关调用情况和健康状态' },
  { icon: '🛠️', text: '演示一次工具调用：查询已注册的 Agent 列表' },
  { icon: '💡', text: '多租户网关的 RBAC 策略应该如何设计？' },
];

export function Chat() {
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [model, setModel] = useState<string>('');
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string>('');
  const [booted, setBooted] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);
  /** 输入框 ref：进入页面/发送完毕自动聚焦（DeepSeek 式） */
  const inputRef = useRef<HTMLTextAreaElement>(null);
  /** 当前流调用句柄：停止生成用 */
  const activeCall = useRef<StreamCall | null>(null);
  /** 消息容器 ref — 用于"粘底跟随"判断和精确滚动 */
  const msgsRef = useRef<HTMLDivElement>(null);
  /** 用户是否"粘底"：靠近底部 80px 内视为粘底。粘底时新消息自动跟随；上滚查看历史时不打断 */
  const stickToBottom = useRef(true);
  /** 有未读新消息时显示的"↓ 新消息"按钮 */
  const [hasNewBelow, setHasNewBelow] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const ms = await listPublicModels();
        setModels(ms);
        if (ms.length > 0) setModel(ms[0].modelId);
        const a = await listAgents();
        setAgents(a);
        const s = await listSessions();
        setSessions(s);
      } catch {
        // offline / not authed
      } finally {
        setBooted(true);
        inputRef.current?.focus();
      }
    })();
  }, []);

  /**
   * 滚动策略：
   * - 粘底（stickToBottom）→ 平滑滚到容器底部，保证新消息可见
   * - 已上滚查看历史 → 不强制滚动，改为显示"↓ 新消息"按钮，避免抢焦点
   */
  useEffect(() => {
    const el = msgsRef.current;
    if (!el) return;
    if (stickToBottom.current) {
      // jsdom 未实现 scrollTo，做能力守卫（测试环境直接跳过）
      if (typeof el.scrollTo === 'function') {
        el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
      }
      setHasNewBelow(false);
    } else {
      setHasNewBelow(true);
    }
  }, [messages, streaming]);

  /** 监听用户手动滚动：一旦上滚超过阈值就脱离粘底 */
  const onMsgsScroll = useCallback(() => {
    const el = msgsRef.current;
    if (!el) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    const STICK_THRESHOLD = 80;
    const wasStuck = stickToBottom.current;
    const isStuck = distance <= STICK_THRESHOLD;
    stickToBottom.current = isStuck;
    if (isStuck && !wasStuck) {
      // 用户主动滚回底部 → 清除"新消息"提示
      setHasNewBelow(false);
    }
  }, []);

  /** 点击"↓ 新消息"按钮 → 滚到底并恢复粘底 */
  const jumpToBottom = useCallback(() => {
    const el = msgsRef.current;
    if (!el) return;
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    }
    stickToBottom.current = true;
    setHasNewBelow(false);
  }, []);

  /**
   * 会话回放：切换会话时拉取历史消息渲染。
   * 后端返回 { role, content }（role='user'|'assistant'|'tool'）；宽容兼容
   * 旧字段 { type } 与 { text } / { message } 形态。tool 消息合并进相邻 assistant 的 toolCalls 展示。
   */
  const openSession = useCallback(
    async (sid: string) => {
      if (streaming) return; // 流式中禁止切换（避免 sink 混写）
      setCurrentId(sid);
      setError('');
      setMessages([]);
      stickToBottom.current = true;
      const target = sessions.find((s) => s.sessionId === sid);
      if (target?.model) setModel(target.model); // 模型跟随会话
      try {
        const raw = await getMessages(sid);
        const parsed: Msg[] = [];
        for (const item of Array.isArray(raw) ? raw : []) {
          const it = item as Record<string, unknown>;
          const rawRole = it?.role ?? it?.type;
          const role =
            rawRole === 'assistant' || rawRole === 'ai' || rawRole === 'bot'
              ? 'assistant'
              : rawRole === 'user' || rawRole === 'human'
                ? 'user'
                : null;
          const agent = it?.agent as string | undefined;
          const content = (it?.content ?? it?.text ?? it?.message) as string | undefined;
          // 工具调用/结果 → 合并为 tag 展示在最近一条 assistant 消息上
          if ((rawRole === 'tool' || rawRole === 'tool_call' || rawRole === 'tool_result') && agent) {
            const last = parsed[parsed.length - 1];
            if (last && last.role === 'assistant') {
              const existing = last.toolCalls ?? [];
              last.toolCalls = [
                ...existing,
                { id: parsed.length * 1000 + existing.length, agent, phase: 'done', success: true },
              ];
            }
            continue;
          }
          if (role && typeof content === 'string' && content) {
            parsed.push({ role, content });
          }
        }
        setMessages(parsed);
      } catch {
        // 历史拉取失败不阻断：留在空会话，用户仍可继续对话
        setMessages([]);
      }
    },
    [sessions, streaming],
  );

  /** 删除会话：hover 删除按钮 → 确认 → 清理本地状态 + 广播 */
  const removeSession = useCallback(
    async (sid: string) => {
      try {
        await deleteSession(sid);
        setSessions((prev) => prev.filter((s) => s.sessionId !== sid));
        if (currentId === sid) {
          setCurrentId(null);
          setMessages([]);
        }
        message.success('会话已删除');
      } catch (e) {
        setError(e instanceof Error ? e.message : '删除会话失败');
      }
    },
    [currentId],
  );

  const newSession = useCallback(() => {
    // DeepSeek 式：新会话是纯前端动作（回到输入框），真正建会话推迟到首条消息发送
    if (streaming) return;
    setCurrentId(null);
    setMessages([]);
    setError('');
    inputRef.current?.focus();
  }, [streaming]);

  /** 刷新侧栏列表（发完消息后调用，带回最新 title/lastActiveAt） */
  const refreshSessions = useCallback(() => {
    listSessions()
      .then(setSessions)
      .catch(() => {});
  }, []);

  /** 停止生成：中止流，保留已生成部分 */
  const stopStreaming = useCallback(() => {
    activeCall.current?.stop();
  }, []);

  /**
   * 内部统一驱动一次对话请求：
   * - targetIdx：null 表示新增一轮 user+assistant；否则是替换指定 assistant 消息（用于重试/再生）
   * - 无会话时（DeepSeek 式首聊）：先自动 createSession
   */
  const runPrompt = useCallback(
    async (prompt: string, targetIdx: number | null) => {
      setError('');
      if (!model) {
        setError('模型尚未加载完成，请稍候再试');
        return;
      }

      // 首聊自动建会话（不需要用户先点"+ 新会话"）
      let sid = currentId;
      if (!sid) {
        try {
          const r = await createSession(model);
          const created = r?.sessionId;
          if (typeof created !== 'string' || created.length === 0) {
            setError('会话创建失败：服务端未返回 sessionId');
            return;
          }
          sid = created;
          setCurrentId(created);
          // 乐观标题：不等后端流结束，侧栏立即显示首条消息摘要（DeepSeek 式）
          const optimistic: Session = {
            sessionId: created,
            lastActiveAt: new Date().toISOString(),
            title: summarizeTitle(prompt),
          };
          setSessions((prev) => [optimistic, ...prev.filter((s) => s.sessionId !== created)]);
          refreshSessions();
        } catch (e: any) {
          setError(e?.message ?? '创建会话失败');
          return;
        }
      }

      if (targetIdx == null) {
        setInput('');
        setMessages((prev) => [
          ...prev,
          { role: 'user', content: prompt },
          { role: 'assistant', content: '', prompt, streaming: true },
        ]);
      } else {
        setMessages((prev) =>
          prev.map((m, i) =>
            i === targetIdx ? { ...m, content: '', streaming: true } : { ...m, streaming: false },
          ),
        );
      }
      setStreaming(true);
      let acc = '';
      const call = streamChat(
        sid,
        prompt,
        model || null,
        (chunk) => {
          acc += chunk;
          setMessages((prev) =>
            prev.map((m, i) => {
              const idx = targetIdx ?? prev.length - 1;
              return i === idx ? { ...m, content: acc, streaming: true, meta: undefined } : m;
            }),
          );
        },
        (agent, phase, success) => {
          setMessages((prev) =>
            prev.map((m, i) => {
              const idx = targetIdx ?? prev.length - 1;
              if (i !== idx || m.role !== 'assistant') return m;
              const calls = [...(m.toolCalls ?? [])];
              if (phase === 'started') {
                calls.push({ id: Date.now() + Math.random(), agent, phase: 'started' });
              } else {
                const k = calls.findLastIndex((c) => c.agent === agent && c.phase === 'started');
                if (k >= 0) calls[k] = { ...calls[k], phase: 'done', success };
              }
              return { ...m, toolCalls: calls };
            }),
          );
        },
        (full, stopped, meta) => {
          setMessages((prev) =>
            prev.map((m, i) => {
              const idx = targetIdx ?? prev.length - 1;
              return i === idx ? { ...m, content: full || acc, streaming: false, meta } : m;
            }),
          );
          setStreaming(false);
          activeCall.current = null;
          refreshSessions();
          if (stopped) message.info('已停止生成');
        },
        (msg) => {
          setError(msg);
          setStreaming(false);
          activeCall.current = null;
          setMessages((prev) =>
            prev.map((m, i) => {
              const idx = targetIdx ?? prev.length - 1;
              return i === idx ? { ...m, streaming: false } : m;
            }),
          );
        },
      );
      activeCall.current = call;
      await call.promise;
    },
    [currentId, model, refreshSessions],
  );

  const send = useCallback(async () => {
    if (!input.trim() || streaming) return;
    const prompt = input.trim();
    await runPrompt(prompt, null);
    inputRef.current?.focus();
  }, [input, streaming, runPrompt]);

  const copyMessage = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      message.success('已复制');
    } catch {
      message.error('复制失败');
    }
  }, []);

  const shareMessage = useCallback(
    async (text: string) => {
      if (navigator.share) {
        try {
          await navigator.share({ text });
          return;
        } catch {
          /* user cancelled */
        }
      }
      // fallback: 复制
      await copyMessage(text);
      message.info('已复制链接片段（浏览器不支持原生分享）');
    },
    [copyMessage],
  );

  const retryAssistant = useCallback(
    async (idx: number) => {
      const m = messages[idx];
      const prompt = m.prompt ?? messages[idx - 1]?.content;
      if (!prompt || streaming) return;
      await runPrompt(prompt, idx);
    },
    [messages, streaming, runPrompt],
  );

  const activeSession = sessions.find((s) => s.sessionId === currentId);

  return (
    <>
      <PageHeader
        eyebrow="Chat · 对话测试"
        title="对话工作台"
        sub="直连 /v1/chat/stream · 工具调用可视化"
        actions={
          <Space>
            <Select
              value={model}
              onChange={setModel}
              style={{ width: 220 }}
              options={models.map((m) => ({
                value: m.modelId,
                label: m.displayName || m.modelId,
              }))}
              placeholder="选择模型"
            />
            <Button icon={<PlusOutlined />} onClick={newSession} disabled={streaming}>
              新会话
            </Button>
          </Space>
        }
      />

      <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 16 }}>
        <div className="content-card" style={{ height: 'fit-content' }}>
          <div className="content-card-head">
            <div className="content-card-title">会话 · {sessions.length}</div>
          </div>
          {sessions.length === 0 ? (
            <Empty description="暂无会话" />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {sessions.map((s) => (
                <div
                  key={s.sessionId}
                  role="button"
                  tabIndex={0}
                  aria-current={currentId === s.sessionId}
                  onClick={() => openSession(s.sessionId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      openSession(s.sessionId);
                    }
                  }}
                  className="chat-session-item"
                  style={{
                    padding: '8px 10px',
                    borderRadius: 'var(--r-sm)',
                    cursor: 'pointer',
                    background:
                      currentId === s.sessionId ? 'var(--brand-amber-soft)' : undefined,
                    borderLeft:
                      currentId === s.sessionId
                        ? '3px solid var(--brand-amber)'
                        : '3px solid transparent',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 4,
                    }}
                  >
                    <div
                      className="chat-session-title"
                      title={s.title || s.sessionId}
                    >
                      {s.title || '新对话'}
                    </div>
                    <Popconfirm
                      title="删除该会话？"
                      description="会话历史将不可恢复"
                      okText="删除"
                      okType="danger"
                      cancelText="取消"
                      onConfirm={(e) => {
                        e?.stopPropagation();
                        removeSession(s.sessionId);
                      }}
                      onCancel={(e) => e?.stopPropagation()}
                    >
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        className="chat-session-del"
                        onClick={(e) => e.stopPropagation()}
                        aria-label={`删除会话 ${s.title || '新对话'}`}
                      />
                    </Popconfirm>
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--text-3)' }}>
                    {relTime(s.lastActiveAt)}
                    {s.messageCount != null && s.messageCount > 0 ? ` · ${s.messageCount} 条` : ''}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <div
          className="content-card"
          style={{
            display: 'flex',
            flexDirection: 'column',
            height: '60vh',
            // 粘在 header 下方下：发消息后页面整体滚动时，对话区始终可见
            position: 'sticky',
            top: 'var(--header-h)',
            zIndex: 1,
          }}
        >
          <div
            ref={msgsRef}
            onScroll={onMsgsScroll}
            style={{
              flex: 1,
              overflowY: 'auto',
              display: 'flex',
              flexDirection: 'column',
              gap: 12,
              position: 'relative',
            }}
          >
            {messages.length === 0 ? (
              booted ? (
                <div className="chat-empty-hero">
                  <div className="chat-empty-hero-logo">
                    <RobotOutlined />
                  </div>
                  <div className="chat-empty-hero-title">
                    {activeSession ? activeSession.title || '继续这段对话' : '开始新的对话'}
                  </div>
                  <div className="chat-empty-hero-sub">
                    直接在下方输入，发送后自动创建会话 · Enter 发送 / Shift+Enter 换行
                  </div>
                  <div className="chat-empty-suggestions">
                    {SUGGESTIONS.map((s) => (
                      <button
                        key={s.text}
                        className="chat-suggestion"
                        onClick={() => {
                          setInput(s.text);
                          inputRef.current?.focus();
                        }}
                      >
                        <span className="chat-suggestion-icon">{s.icon}</span>
                        <span>{s.text}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ) : null
            ) : (
              messages.map((m, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: m.role === 'user' ? 'flex-end' : 'flex-start',
                  }}
                >
                  {m.toolCalls && m.toolCalls.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 6 }}>
                      {m.toolCalls.map((tc) => {
                        const color =
                          tc.phase === 'started' ? 'processing' : tc.success ? 'success' : 'error';
                        return (
                          <Tag key={tc.id} color={color}>
                            {tc.agent} ·{' '}
                            {tc.phase === 'started' ? '调用中…' : tc.success ? '完成' : '失败'}
                          </Tag>
                        );
                      })}
                    </div>
                  )}
                  <div
                    className={`chat-bubble chat-bubble--${m.role}`}
                    style={{
                      maxWidth: '85%',
                      padding: m.role === 'assistant' ? '8px 14px' : '10px 14px',
                      fontSize: 13,
                      lineHeight: 1.6,
                      width: 'fit-content',
                      wordBreak: 'break-word',
                    }}
                  >
                    {m.role === 'assistant' ? (
                      m.content ? (
                        <MarkdownView source={m.content} />
                      ) : m.streaming && i === messages.length - 1 ? (
                        <span className="chat-cursor" />
                      ) : null
                    ) : (
                      <span style={{ whiteSpace: 'pre-wrap' }}>{m.content}</span>
                    )}
                  </div>

                  {/* 用量角标：实际命中模型（灰度分流后）+ token 估算（chars/4 口径） */}
                  {m.role === 'assistant' && m.meta && !m.streaming && (
                    <div
                      style={{
                        marginTop: 2,
                        fontSize: 11,
                        opacity: 0.55,
                        display: 'flex',
                        gap: 8,
                        paddingLeft: 2,
                      }}
                      className="msg-meta"
                    >
                      <span>{m.meta.model}</span>
                      <span>
                        ↑{m.meta.tokensIn.toLocaleString()} ↓{m.meta.tokensOut.toLocaleString()} tokens（估算）
                      </span>
                      {m.meta.cacheHit === true && (
                        <Tag
                          color="green"
                          data-testid="cache-hit-tag"
                          style={{ marginInlineEnd: 0, fontSize: 10, lineHeight: '16px', paddingInline: 4 }}
                        >
                          缓存命中
                        </Tag>
                      )}
                    </div>
                  )}

                  {/* 消息操作条：assistant 显示复制/重试/分享；user 显示复制/分享 */}
                  {m.content && !streaming && (
                    <Space size={4} style={{ marginTop: 4, opacity: 0.7 }} className="msg-actions">
                      <Tooltip title="复制">
                        <Button
                          type="text"
                          size="small"
                          icon={<CopyOutlined />}
                          onClick={() => copyMessage(m.content)}
                          aria-label="复制消息"
                        />
                      </Tooltip>
                      <Tooltip title="分享">
                        <Button
                          type="text"
                          size="small"
                          icon={<ShareAltOutlined />}
                          onClick={() => shareMessage(m.content)}
                          aria-label="分享消息"
                        />
                      </Tooltip>
                      {m.role === 'assistant' && (
                        <Tooltip title="重新生成">
                          <Button
                            type="text"
                            size="small"
                            icon={<ReloadOutlined spin={m.streaming} />}
                            disabled={streaming}
                            onClick={() => retryAssistant(i)}
                            aria-label="重新生成"
                          />
                        </Tooltip>
                      )}
                      {m.role === 'assistant' && (
                        // Round 11: 反馈标注挂在每条 assistant 消息下
                        // traceId 暂用 messageIndex + content hash 派生（前端稳定标识）
                        <FeedbackButtons
                          traceId={`chat-${i}-${hashCode(m.content).toString(16)}`}
                          model={m.meta?.model}
                          size="small"
                        />
                      )}
                    </Space>
                  )}
                </div>
              ))
            )}
            <div ref={endRef} />
            {hasNewBelow && (
              <Button
                size="small"
                shape="round"
                icon={<ArrowDownOutlined />}
                onClick={jumpToBottom}
                style={{
                  position: 'sticky',
                  bottom: 8,
                  alignSelf: 'center',
                  marginTop: 8,
                  zIndex: 2,
                  boxShadow: '0 4px 12px rgba(0,0,0,.18)',
                  background: 'var(--brand-amber)',
                  borderColor: 'var(--brand-amber)',
                  color: 'var(--brand-deep)',
                  fontWeight: 500,
                }}
              >
                新消息 ↓
              </Button>
            )}
          </div>

          {error && (
            <div
              style={{
                padding: 8,
                marginTop: 8,
                fontSize: 12,
                color: 'var(--ant-error)',
                background: 'rgba(255,77,79,.06)',
                border: '1px solid rgba(255,77,79,.25)',
                borderRadius: 'var(--r-sm)',
              }}
            >
              ⚠ {error}
            </div>
          )}

          <Space.Compact style={{ marginTop: 12, width: '100%' }}>
            <Input.TextArea
              ref={inputRef as any}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="输入消息，Enter 发送 · Shift+Enter 换行"
              autoSize={{ minRows: 1, maxRows: 6 }}
              disabled={!booted}
              onPressEnter={(e) => {
                if (!e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
            />
            {streaming ? (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={stopStreaming}
                style={{ height: 'auto' }}
              >
                停止
              </Button>
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                disabled={!input.trim()}
                onClick={send}
              >
                发送
              </Button>
            )}
          </Space.Compact>
        </div>
      </div>

      <div className="content-card" style={{ marginTop: 16 }}>
        <div className="content-card-head">
          <div className="content-card-title">已注册 Agents · {agents.length}</div>
        </div>
        {agents.length === 0 ? (
          <Empty description="暂无可用 Agent" />
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))',
              gap: 12,
            }}
          >
            {agents.map((a) => (
              <div
                key={a.name}
                style={{
                  padding: 12,
                  background: 'var(--bg-sunken)',
                  border: '1px solid var(--border-thin)',
                  borderRadius: 'var(--r-md)',
                }}
              >
                <strong style={{ color: 'var(--brand-amber)' }}>{a.name}</strong>
                <div style={{ fontSize: 12, color: 'var(--text-3)', marginTop: 4 }}>
                  {a.description}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );
}

/** 简单字符串 hash（djb2 变种）—— 用于派生前端稳定的 traceId。 */
function hashCode(s: string): number {
  let h = 5381;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}
