/**
 * tests/api-explorer-openai.test.tsx — OpenAI 兼容模式
 *
 * 分两部分（client 层在前，页面层在后）：
 *
 * 客户端层（不依赖 React，直接对 lib/api/openaiCompat 单元驱动）：
 *  1) 非流式成功：构造 { choices: [{message:{content}}], usage }，断言 content + usage
 *  2) 流式：喂入 ReadableStream（role 首帧 + 2 个 content 帧 + [DONE]），
 *     断言 onChunk 调用 2 次、拼接正确、onDone 触发一次
 *  3) [DONE] 不触发 JSON.parse 异常：断言 onError 未被调用
 *  4) 后端 401 + {error:{message:'invalid key', code:'invalid_api_key'}}
 *     → onError 收到 'invalid key'
 *
 * 页面层（ApiExplorer 渲染 + tab 切换 + 试跑）：
 *  5) 渲染 ApiExplorer → 切到兼容模式 → 断言样例代码块与试跑表单存在
 *  6) mock openaiCompat 模块后点发送，断言输出区出现返回内容与 usage 数值
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { installMock } from './fixtures/mockServer';
import { renderWithRouter } from './harness';

// =====================================================================
// 客户端层
// =====================================================================

describe('openaiCompat client — non-stream + stream + error paths', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    localStorage.clear();
    mock = installMock();
  });
  afterEach(() => mock.uninstall());

  it('非流式成功：解析 content 与 usage', async () => {
    mock.on('POST', '/v1/chat/completions', () =>
      new Response(
        JSON.stringify({
          id: 'chatcmpl-1',
          model: 'gpt-4o',
          choices: [{ index: 0, message: { role: 'assistant', content: '你好，世界。' } }],
          usage: { prompt_tokens: 11, completion_tokens: 5, total_tokens: 16 },
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );

    const { callOpenAiCompletions } = await import('../src/lib/api/openaiCompat');
    const res = await callOpenAiCompletions(
      [{ role: 'user', content: '你好' }],
      'gpt-4o',
      false,
    );
    expect(res.id).toBe('chatcmpl-1');
    expect(res.model).toBe('gpt-4o');
    expect(res.content).toBe('你好，世界。');
    expect(res.usage).toEqual({
      prompt_tokens: 11,
      completion_tokens: 5,
      total_tokens: 16,
    });
  });

  it('流式：role 首帧 + 2 个 content 帧 + [DONE] → onChunk 调 2 次 + onDone 调 1 次', async () => {
    // 构造 OpenAI 兼容 SSE 流（裸 data: 行，无 event: 前缀）
    const sse = [
      'data: {"id":"x","choices":[{"delta":{"role":"assistant"}}]}',
      '',
      'data: {"choices":[{"delta":{"content":"你"}}]}',
      '',
      'data: {"choices":[{"delta":{"content":"好"}}]}',
      '',
      'data: {"choices":[{"delta":{"content":"，"}}]}',
      '',
      'data: [DONE]',
      '',
    ].join('\n');

    const stream = new ReadableStream({
      start(controller) {
        const enc = new TextEncoder();
        controller.enqueue(enc.encode(sse));
        controller.close();
      },
    });
    mock.on('POST', '/v1/chat/completions', () =>
      new Response(stream, { headers: { 'content-type': 'text/event-stream' } }),
    );

    const { streamOpenAiCompletions } = await import('../src/lib/api/openaiCompat');
    const onChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { promise } = streamOpenAiCompletions(
      [{ role: 'user', content: 'hi' }],
      'gpt-4o',
      onChunk,
      onDone,
      onError,
    );
    await promise;

    expect(onChunk).toHaveBeenCalledTimes(3);
    expect(onChunk.mock.calls.map((c) => c[0]).join('')).toBe('你好，');
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('[DONE] 不触发 JSON.parse 异常（断言 onError 未被调用）', async () => {
    // 故意把 [DONE] 之前塞一条畸形帧，验证 JSON.parse 失败被吞掉不打断整流
    const sse = [
      'data: {not-json}',
      '',
      'data: {"choices":[{"delta":{"content":"ok"}}]}',
      '',
      'data: [DONE]',
      '',
    ].join('\n');
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(sse));
        controller.close();
      },
    });
    mock.on('POST', '/v1/chat/completions', () =>
      new Response(stream, { headers: { 'content-type': 'text/event-stream' } }),
    );

    const { streamOpenAiCompletions } = await import('../src/lib/api/openaiCompat');
    const onChunk = vi.fn();
    const onDone = vi.fn();
    const onError = vi.fn();
    const { promise } = streamOpenAiCompletions(
      [{ role: 'user', content: 'hi' }],
      'gpt-4o',
      onChunk,
      onDone,
      onError,
    );
    await promise;
    expect(onError).not.toHaveBeenCalled();
    expect(onChunk).toHaveBeenCalledTimes(1);
    expect(onChunk.mock.calls[0][0]).toBe('ok');
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('后端 401 + {error:{message:"invalid key", code:"invalid_api_key"}} → onError 收到 "invalid key"', async () => {
    mock.on('POST', '/v1/chat/completions', () =>
      new Response(
        JSON.stringify({ error: { message: 'invalid key', code: 'invalid_api_key' } }),
        { status: 401, headers: { 'content-type': 'application/json' } },
      ),
    );
    const { callOpenAiCompletions } = await import('../src/lib/api/openaiCompat');
    await expect(
      callOpenAiCompletions([{ role: 'user', content: 'hi' }], 'gpt-4o', false),
    ).rejects.toThrow('invalid key');
  });

  it('流式鉴权头同时发 Bearer + X-API-Key + X-Tenant-Id', async () => {
    // 让后端把请求头写到一个 JSON 里返回（mock 简化：仅检查 fetch 调用）
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: 'x',
          model: 'gpt-4o',
          choices: [{ message: { role: 'assistant', content: 'hi' } }],
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchSpy);

    const { callOpenAiCompletions } = await import('../src/lib/api/openaiCompat');
    await callOpenAiCompletions([{ role: 'user', content: 'hi' }], 'gpt-4o', false);

    const [, init] = fetchSpy.mock.calls[0];
    const h = (init as RequestInit).headers as Record<string, string>;
    // bearer + X-API-Key 都应携带（后端从 Bearer 剥取 token）
    expect(h['Authorization']).toMatch(/^Bearer /);
    expect(h['X-API-Key']).toBeTruthy();
    expect(h['X-Tenant-Id']).toBe('primary');
  });
});

// =====================================================================
// 页面层
// =====================================================================

describe('ApiExplorer — OpenAI 兼容模式面板', () => {
  let mock: ReturnType<typeof installMock>;

  beforeEach(() => {
    localStorage.clear();
    mock = installMock();
    // 默认文档里至少要有一个端点，否则「API 文档」视图会显示 Empty，但
    // 我们切到兼容模式不依赖它。这里放个 dummy 即可。
    mock.on('GET', '/v1/openapi.json', () =>
      new Response(
        JSON.stringify({
          openapi: '3.0.3',
          info: { title: 't', version: '1' },
          paths: {},
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
  });
  afterEach(() => mock.uninstall());

  it('渲染 ApiExplorer → 切到兼容模式 → 断言样例代码块与试跑表单存在', async () => {
    const { ApiExplorer } = await import('../src/pages/ApiExplorer');
    renderWithRouter(<ApiExplorer />);

    // 默认在「API 文档」标签
    expect(screen.getByRole('tab', { name: /API 文档/ })).toBeInTheDocument();

    // 切到 OpenAI 兼容模式
    fireEvent.click(screen.getByRole('tab', { name: /OpenAI 兼容模式/ }));

    // Alert 提示
    await waitFor(() => {
      expect(
        screen.getByText(/让现有 OpenAI SDK 零改造接入/),
      ).toBeInTheDocument();
    });

    // 样例代码：python + curl 各一块（用 getByText 找 code 内容）
    expect(screen.getByText(/import openai/)).toBeInTheDocument();
    expect(screen.getByText(/^curl .*chat\/completions/m)).toBeInTheDocument();

    // 试跑表单元素
    expect(screen.getByPlaceholderText(/gpt-4o \/ claude/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('user 消息内容')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /发送/ })).toBeInTheDocument();
  });

  it('mock 掉 openaiCompat 模块后点发送，断言输出区出现返回内容与 usage 数值', async () => {
    // 用 hoist-safe vi.mock 顶替模块；因为 ApiExplorer 内部静态 import openaiCompat，
    // vi.doMock 必须在 ApiExplorer 加载前生效 —— 改用顶层 hoisted vi.mock 写法。
    // 由于本测试只此一项使用 mock，置于顶层会污染其他 describe；改用 importActual
    // 路线：直接 spy 真实模块的导出（callOpenAiCompletions 是函数对象，可被替换）。
    const openaiCompat = await import('../src/lib/api/openaiCompat');
    const realCall = openaiCompat.callOpenAiCompletions;
    const spy = vi
      .spyOn(openaiCompat, 'callOpenAiCompletions')
      .mockResolvedValue({
        id: 'chatcmpl-test',
        model: 'gpt-4o',
        content: '【mock 回复】你好，我是网关。',
        usage: { prompt_tokens: 9, completion_tokens: 13, total_tokens: 22 },
      });

    const { ApiExplorer } = await import('../src/pages/ApiExplorer');
    renderWithRouter(<ApiExplorer />);

    // 切到 OpenAI 兼容模式
    fireEvent.click(screen.getByRole('tab', { name: /OpenAI 兼容模式/ }));

    // 等表单渲染好
    await screen.findByPlaceholderText('user 消息内容');

    // 直接点发送（默认 prompt 已预填）
    fireEvent.click(screen.getByRole('button', { name: /发送/ }));

    // 输出区出现返回内容（用 testid 拿）
    await waitFor(() => {
      const out = screen.getByTestId('oa-output');
      expect(out.textContent).toContain('【mock 回复】你好，我是网关。');
    });
    // usage 三个 Tag 都出现（用 testid 拿）
    const usage = screen.getByTestId('oa-usage');
    expect(usage.textContent).toContain('prompt 9');
    expect(usage.textContent).toContain('completion 13');
    expect(usage.textContent).toContain('total 22');
    expect(spy).toHaveBeenCalled();

    spy.mockRestore();
    void realCall;
  });
});
