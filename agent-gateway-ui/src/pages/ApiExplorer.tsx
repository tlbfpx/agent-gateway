/**
 * ApiExplorer — API 浏览器（Swagger 替代品）+ OpenAI 兼容模式
 *
 * - 顶部 Tabs 切换两个视图（默认停留在「API 文档」，保持既有用户路径）
 *   1) API 文档：拉取 /v1/openapi.json → 按 tag 分组渲染端点 → Try it 试发
 *   2) OpenAI 兼容模式：演示上游 OpenAI SDK 零改造接入（base_url + api_key）
 *      + 最小对话试跑区（模型 / 消息 / 流式开关 → 实时输出 + usage）
 *
 * 不引 swagger-ui-react（~600KB），自研轻量 viewer。
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Tag,
  Button,
  Input,
  Space,
  Empty,
  Select,
  Tooltip,
  message,
  Spin,
  Alert,
  Tabs,
  Switch,
} from 'antd';
import {
  ReloadOutlined,
  ApiOutlined,
  CaretRightOutlined,
  PlayCircleOutlined,
  CopyOutlined,
  ThunderboltOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import { PageHeader } from '../components/framework/PageHeader';
import { BundleDownloader } from '../components/openapi/BundleDownloader';
import { fetchOpenApi, groupByTag, flattenEndpoints } from '../lib/api/openapi';
import type { ApiEndpoint } from '../lib/api/openapi';
import { getApiKey, getTenant } from '../lib/request';
import { ErrorState } from '../components/framework/EmptyState';
import {
  callOpenAiCompletions,
  streamOpenAiCompletions,
  type OaMessage,
  type OaUsage,
} from '../lib/api/openaiCompat';
import { CodeSnippet } from '../components/CodeSnippet';
import type { CodegenRequest } from '../lib/codegen';

const METHOD_COLOR: Record<string, string> = {
  GET: 'blue',
  POST: 'green',
  PUT: 'orange',
  DELETE: 'red',
  PATCH: 'purple',
};

export function ApiExplorer() {
  return (
    <>
      <PageHeader
        eyebrow="API · 接口"
        title="API 浏览器"
        sub="OpenAPI 3.0 · /v1/openapi.json · 也支持 OpenAI 兼容模式"
      />
      <Tabs
        defaultActiveKey="docs"
        items={[
          {
            key: 'docs',
            label: 'API 文档',
            children: <ApiDocsView />,
          },
          {
            key: 'openai',
            label: (
              <span>
                <ThunderboltOutlined /> OpenAI 兼容模式
              </span>
            ),
            children: <OpenAiCompatView />,
          },
        ]}
      />
    </>
  );
}

/**
 * 「API 文档」视图 —— 与 Round4 行为零变化（tag 过滤、搜索、Try it 照旧）。
 */
function ApiDocsView() {
  const [endpoints, setEndpoints] = useState<ApiEndpoint[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [tagFilter, setTagFilter] = useState<string | undefined>();
  const [search, setSearch] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const doc = await fetchOpenApi();
      setEndpoints(flattenEndpoints(doc));
    } catch (e: any) {
      setError(e?.message ?? '拉取 OpenAPI 失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const filtered = useMemo(() => {
    let list = endpoints;
    if (tagFilter) list = list.filter((e) => e.tags[0] === tagFilter);
    if (search) {
      const s = search.toLowerCase();
      list = list.filter(
        (e) =>
          e.path.toLowerCase().includes(s) ||
          e.method.toLowerCase().includes(s) ||
          e.summary.toLowerCase().includes(s),
      );
    }
    return list;
  }, [endpoints, tagFilter, search]);

  const grouped = useMemo(() => groupByTag(filtered), [filtered]);
  const tagOptions = useMemo(() => {
    const tags = new Set(endpoints.map((e) => e.tags[0] ?? '未分类'));
    return Array.from(tags).map((t) => ({ value: t, label: t }));
  }, [endpoints]);

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          placeholder="搜索 path / method / summary"
          style={{ width: 240 }}
          allowClear
          onChange={(e) => setSearch(e.target.value)}
        />
        <Select
          allowClear
          placeholder="按 tag 筛选"
          style={{ width: 180 }}
          value={tagFilter}
          onChange={setTagFilter}
          options={tagOptions}
        />
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
        <BundleDownloader />
        <Tag style={{ margin: 0 }}>{endpoints.length} 个端点</Tag>
      </Space>

      {error && <ErrorState error={error} onRetry={load} retryLabel="重新加载" />}

      {loading && endpoints.length === 0 && (
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spin /> <span style={{ marginLeft: 8, color: 'var(--text-3)' }}>拉取 OpenAPI…</span>
        </div>
      )}

      {!loading && !error && endpoints.length === 0 && (
        <Empty description="未发现任何端点" />
      )}

      {Array.from(grouped.entries()).map(([tag, eps]) => (
        <div key={tag} className="content-card" style={{ marginBottom: 16 }}>
          <div className="content-card-head">
            <div className="content-card-title">
              <ApiOutlined /> {tag} · {eps.length}
            </div>
          </div>
          {eps.map((e) => (
            <EndpointRow key={`${e.method}-${e.path}`} ep={e} />
          ))}
        </div>
      ))}
    </>
  );
}

/**
 * 「OpenAI 兼容模式」视图 —— 演示上游 OpenAI SDK 零改造接入 + 试跑对话。
 */
function OpenAiCompatView() {
  // 动态拼 base_url，避免硬编码域名（部署到任意网关都能用）
  const baseUrl = useMemo(() => {
    const origin =
      typeof window !== 'undefined' && window.location?.origin
        ? window.location.origin
        : '';
    return `${origin}/v1`;
  }, []);

  // 样例代码 —— 展示给上游用户复制使用，base_url 用动态 origin
  const samplePy = useMemo(
    () => `import openai

client = openai.OpenAI(
    base_url="${baseUrl}",
    api_key="${getApiKey() || 'YOUR_GATEWAY_KEY'}",
)

resp = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "你好，请自我介绍"}],
)
print(resp.choices[0].message.content)`,
    [baseUrl],
  );

  const sampleCurl = useMemo(
    () => `curl ${baseUrl}/chat/completions \\
  -H "Authorization: Bearer ${getApiKey() || 'YOUR_GATEWAY_KEY'}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "你好"}]
  }'`,
    [baseUrl],
  );

  // 试跑表单
  const [model, setModel] = useState('gpt-4o');
  const [prompt, setPrompt] = useState('用一句话介绍 Agent Gateway');
  const [stream, setStream] = useState(false);
  const [running, setRunning] = useState(false);
  const [output, setOutput] = useState('');
  const [usage, setUsage] = useState<OaUsage | undefined>();
  const [errMsg, setErrMsg] = useState('');
  const stopRef = useRef<(() => void) | null>(null);

  const run = async () => {
    if (!prompt.trim()) {
      message.warning('请输入消息内容');
      return;
    }
    setRunning(true);
    setOutput('');
    setUsage(undefined);
    setErrMsg('');
    const messages: OaMessage[] = [{ role: 'user', content: prompt.trim() }];
    try {
      if (stream) {
        const handle = streamOpenAiCompletions(
          messages,
          model.trim() || 'gpt-4o',
          (chunk) => {
            setOutput((s) => s + chunk);
          },
          () => {
            setRunning(false);
            stopRef.current = null;
          },
          (msg) => {
            setErrMsg(msg);
            setRunning(false);
            stopRef.current = null;
          },
        );
        stopRef.current = handle.stop;
        await handle.promise;
      } else {
        const res = await callOpenAiCompletions(messages, model.trim() || 'gpt-4o', false);
        setOutput(res.content);
        setUsage(res.usage);
      }
    } catch (e: any) {
      setErrMsg(e?.message ?? '请求失败');
    } finally {
      setRunning(false);
      stopRef.current = null;
    }
  };

  const stop = () => {
    stopRef.current?.();
    stopRef.current = null;
    setRunning(false);
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="OpenAI 兼容模式：让现有 OpenAI SDK 零改造接入"
        description="只需把 base_url 指向本网关 /v1，api_key 用网关签发的 Key。所有 chat.completions / embeddings 请求都会被路由到本网关统一鉴权、计费、限流和审计。"
      />

      {/* 复制代码样例 */}
      <div className="content-card">
        <div className="content-card-head">
          <div className="content-card-title">接入样例（点击右侧图标复制）</div>
        </div>
        <CodeBlock code={samplePy} lang="python" />
        <div style={{ height: 8 }} />
        <CodeBlock code={sampleCurl} lang="bash" />
      </div>

      {/* 试跑区 */}
      <div className="content-card">
        <div className="content-card-head">
          <div className="content-card-title">
            <PlayCircleOutlined /> 试跑对话
          </div>
        </div>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Space wrap>
            <Space.Compact>
              <span
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  padding: '0 8px',
                  background: 'var(--bg-sunken, rgba(0,0,0,0.02))',
                  border: '1px solid var(--border-thin, #d9d9d9)',
                  borderRight: 0,
                  borderTopLeftRadius: 6,
                  borderBottomLeftRadius: 6,
                  fontSize: 12,
                  color: 'var(--text-2)',
                }}
              >
                model
              </span>
              <Input
                value={model}
                onChange={(e) => setModel(e.target.value)}
                placeholder="gpt-4o / claude-3-5-sonnet / ..."
                style={{ width: 260 }}
                allowClear
              />
            </Space.Compact>
            <Space size={6}>
              <Switch checked={stream} onChange={setStream} />
              <span style={{ color: 'var(--text-2)', fontSize: 12 }}>流式输出（stream=true）</span>
            </Space>
            {running ? (
              <Button danger onClick={stop} icon={<ApiOutlined />}>
                停止
              </Button>
            ) : (
              <Button type="primary" onClick={run} icon={<PlayCircleOutlined />}>
                发送
              </Button>
            )}
          </Space>
          <Input.TextArea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            autoSize={{ minRows: 3, maxRows: 8 }}
            placeholder="user 消息内容"
          />

          {/* 输出区：错误红 / 内容黑 */}
          {errMsg && (
            <div
              role="alert"
              style={{
                background: 'rgba(255, 77, 79, 0.08)',
                border: '1px solid rgba(255, 77, 79, 0.4)',
                color: 'var(--ant-error, #ff4d4f)',
                padding: 12,
                borderRadius: 6,
                fontSize: 13,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              ✗ {errMsg}
            </div>
          )}
          {output && (
            <pre
              className="mono"
              data-testid="oa-output"
              style={{
                background: '#0F1B3D',
                color: '#E8ECF7',
                padding: 12,
                borderRadius: 6,
                fontSize: 12,
                maxHeight: 320,
                overflow: 'auto',
                margin: 0,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {output}
            </pre>
          )}
          {usage && (
            <Space size={6} wrap data-testid="oa-usage">
              <Tag color="blue">prompt {usage.prompt_tokens}</Tag>
              <Tag color="geekblue">completion {usage.completion_tokens}</Tag>
              <Tag color="purple">total {usage.total_tokens}</Tag>
            </Space>
          )}
        </Space>
      </div>
    </Space>
  );
}

function CodeBlock({ code, lang }: { code: string; lang: string }) {
  return (
    <div
      style={{
        position: 'relative',
        background: '#0F1B3D',
        color: '#E8ECF7',
        padding: 12,
        paddingRight: 40,
        borderRadius: 6,
        fontSize: 12,
        fontFamily: 'var(--font-mono)',
        whiteSpace: 'pre',
        overflow: 'auto',
      }}
    >
      <Tooltip title="复制">
        <Button
          size="small"
          type="text"
          icon={<CopyOutlined style={{ color: '#E8ECF7' }} />}
          onClick={() => {
            navigator.clipboard.writeText(code).then(
              () => message.success('已复制'),
              () => message.error('复制失败'),
            );
          }}
          style={{ position: 'absolute', top: 6, right: 6 }}
          aria-label="复制代码"
        />
      </Tooltip>
      <div style={{ fontSize: 10, opacity: 0.6, marginBottom: 6 }}>{lang}</div>
      <div>{code}</div>
    </div>
  );
}

function EndpointRow({ ep }: { ep: ApiEndpoint }) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ borderBottom: '1px solid var(--border-thin)' }}>
      <div
        onClick={() => setOpen(!open)}
        style={{
          padding: '10px 12px',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          cursor: 'pointer',
          background: open ? 'var(--bg-sunken)' : 'transparent',
        }}
      >
        <CaretRightOutlined
          style={{
            color: 'var(--text-3)',
            transform: open ? 'rotate(90deg)' : 'rotate(0)',
            transition: 'transform 120ms',
            fontSize: 11,
          }}
        />
        <Tag color={METHOD_COLOR[ep.method] ?? 'default'} style={{ minWidth: 56, textAlign: 'center', fontFamily: 'var(--font-mono)' }}>
          {ep.method}
        </Tag>
        <span className="mono" style={{ fontSize: 13, color: 'var(--text-1)', fontWeight: 500 }}>
          {ep.path}
        </span>
        {ep.summary && (
          <span style={{ fontSize: 12, color: 'var(--text-3)' }}>— {ep.summary}</span>
        )}
      </div>
      {open && (
        <div style={{ padding: '12px 16px 16px 44px', background: 'var(--bg-sunken)' }}>
          {ep.description && (
            <Alert
              type="info"
              showIcon
              message={ep.description}
              style={{ marginBottom: 12 }}
              closable={false}
            />
          )}

          {/* 参数表 */}
          {ep.op.parameters && ep.op.parameters.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              <div style={sectionLabel}>参数</div>
              <table style={paramTable}>
                <thead>
                  <tr>
                    <th style={th}>名称</th>
                    <th style={th}>位置</th>
                    <th style={th}>类型</th>
                    <th style={th}>必填</th>
                    <th style={th}>说明</th>
                  </tr>
                </thead>
                <tbody>
                  {ep.op.parameters.map((p) => (
                    <tr key={p.name}>
                      <td style={td}><code className="mono">{p.name}</code></td>
                      <td style={td}><Tag>{p.in}</Tag></td>
                      <td style={td}>{p.schema?.type ?? '—'}</td>
                      <td style={td}>{p.required ? <Tag color="red">必填</Tag> : '可选'}</td>
                      <td style={{ ...td, color: 'var(--text-3)' }}>{p.description ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* 响应 */}
          {ep.op.responses && (
            <div style={{ marginBottom: 12 }}>
              <div style={sectionLabel}>响应</div>
              <Space wrap>
                {Object.entries(ep.op.responses).map(([code, r]) => (
                  <Tag key={code} color={code.startsWith('2') ? 'success' : code.startsWith('4') ? 'warning' : 'default'}>
                    {code} · {r.description ?? ''}
                  </Tag>
                ))}
              </Space>
            </div>
          )}

          <TryIt ep={ep} />
        </div>
      )}
    </div>
  );
}

function TryIt({ ep }: { ep: ApiEndpoint }) {
  const [params, setParams] = useState<Record<string, string>>({});
  const [body, setBody] = useState<string>('{\n  \n}');
  const [resp, setResp] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<number | null>(null);
  const [showSnippet, setShowSnippet] = useState(false);

  /**
   * 把当前表单状态拼成可发送的 HTTP 请求描述。
   * submit() 和 CodeSnippet 共用这一份逻辑，确保「复制出的代码」与「实际发出请求」一致。
   */
  const buildRequest = (): { req: CodegenRequest; init: RequestInit; finalUrl: string } => {
    const apiKey = getApiKey();
    const tenant = getTenant();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (apiKey) headers['X-API-Key'] = apiKey;
    if (tenant) headers['X-Tenant-Id'] = tenant;

    // 构造 URL：path 中的 {id} 替换为 params.path.*；query 用 ? 串起来
    let url = ep.path;
    const query: Record<string, string> = {};
    for (const p of ep.op.parameters ?? []) {
      const val = params[p.name];
      if (!val) continue;
      if (p.in === 'path') {
        url = url.replace(`{${p.name}}`, encodeURIComponent(val));
      } else if (p.in === 'query') {
        query[p.name] = val;
      } else if (p.in === 'header') {
        headers[p.name] = val;
      }
    }

    const init: RequestInit = { method: ep.method, headers };
    if (['POST', 'PUT', 'PATCH'].includes(ep.method) && body.trim()) {
      init.body = body;
    }

    // 最终 URL：origin + path + query
    const origin = typeof window !== 'undefined' && window.location?.origin ? window.location.origin : '';
    const finalUrl = `${origin}${url}`;
    const qs = Object.entries(query)
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
      .join('&');
    const fullUrl = qs ? `${finalUrl}?${qs}` : finalUrl;

    // body 解析为对象（若合法 JSON）以让 codegen 输出更漂亮
    let bodyForCode: unknown = undefined;
    if (init.body && typeof init.body === 'string') {
      try {
        bodyForCode = JSON.parse(init.body);
      } catch {
        bodyForCode = init.body;
      }
    }

    const req: CodegenRequest = {
      method: ep.method as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
      url: fullUrl,
      headers,
      body: bodyForCode,
    };

    return { req, init, finalUrl: fullUrl };
  };

  const submit = async () => {
    setLoading(true);
    setResp('');
    try {
      const { init, finalUrl } = buildRequest();
      const r = await fetch(finalUrl, init);
      setStatus(r.status);
      const text = await r.text();
      try {
        setResp(JSON.stringify(JSON.parse(text), null, 2));
      } catch {
        setResp(text);
      }
    } catch (e: any) {
      setResp(`✗ ${e?.message ?? '请求失败'}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        background: 'var(--bg-surface)',
        border: '1px solid var(--border-thin)',
        borderRadius: 6,
        padding: 12,
      }}
    >
      <div style={sectionLabel}>Try it</div>

      {(ep.op.parameters ?? []).filter((p) => p.in === 'query' || p.in === 'path').length > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 8, marginBottom: 8 }}>
          {(ep.op.parameters ?? [])
            .filter((p) => p.in === 'query' || p.in === 'path')
            .map((p) => (
              <Input
                key={p.name}
                size="small"
                placeholder={`${p.name}${p.required ? ' *' : ''} (${p.in})`}
                value={params[p.name] ?? ''}
                onChange={(e) => setParams((s) => ({ ...s, [p.name]: e.target.value }))}
              />
            ))}
        </div>
      )}

      {['POST', 'PUT', 'PATCH'].includes(ep.method) && (
        <Input.TextArea
          value={body}
          onChange={(e) => setBody(e.target.value)}
          autoSize={{ minRows: 4, maxRows: 12 }}
          style={{ fontFamily: 'var(--font-mono)', fontSize: 12, marginBottom: 8 }}
          placeholder='{"key":": value"}'
        />
      )}

      <Space>
        <Button
          type="primary"
          icon={<PlayCircleOutlined />}
          loading={loading}
          onClick={submit}
          size="small"
        >
          发送
        </Button>
        {status != null && (
          <Tag color={status >= 200 && status < 300 ? 'success' : status >= 400 ? 'error' : 'default'}>
            {status}
          </Tag>
        )}
        <Tooltip title={showSnippet ? '隐藏代码片段' : '显示 4 语言代码片段（cURL / Python / JS / Go）'}>
          <Button
            size="small"
            icon={<CodeOutlined />}
            data-testid="toggle-code-snippet"
            onClick={() => setShowSnippet((v) => !v)}
          >
            {showSnippet ? '隐藏代码' : '代码'}
          </Button>
        </Tooltip>
        <Tooltip title="复制响应">
          <Button
            size="small"
            icon={<CopyOutlined />}
            disabled={!resp}
            onClick={() => {
              navigator.clipboard.writeText(resp).then(() => message.success('已复制'));
            }}
          />
        </Tooltip>
      </Space>

      {showSnippet && (
        <div style={{ marginTop: 8 }} data-testid="tryit-code-snippet">
          <CodeSnippet request={buildRequest().req} defaultLang="curl" />
        </div>
      )}

      {resp && (
        <pre
          className="mono"
          style={{
            marginTop: 8,
            background: '#0F1B3D',
            color: '#E8ECF7',
            padding: 12,
            borderRadius: 6,
            fontSize: 12,
            maxHeight: 240,
            overflow: 'auto',
          }}
        >
          {resp}
        </pre>
      )}
    </div>
  );
}

const sectionLabel: React.CSSProperties = {
  fontSize: 11,
  fontFamily: 'var(--font-mono)',
  letterSpacing: 1,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  marginBottom: 6,
};

const paramTable: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: 12,
};
const th: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 8px',
  background: 'var(--bg-sunken)',
  borderBottom: '1px solid var(--border-thin)',
  fontWeight: 500,
  color: 'var(--text-2)',
};
const td: React.CSSProperties = {
  padding: '6px 8px',
  borderBottom: '1px dashed var(--border-thin)',
};